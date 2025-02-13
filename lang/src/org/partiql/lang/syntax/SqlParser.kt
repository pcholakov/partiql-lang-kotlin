/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates.  All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 *  You may not use this file except in compliance with the License.
 * A copy of the License is located at:
 *
 *      http://aws.amazon.com/apache2.0/
 *
 *  or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
 *  language governing permissions and limitations under the License.
 */

package org.partiql.lang.syntax

import com.amazon.ion.*
import org.partiql.lang.ast.*
import org.partiql.lang.ast.passes.*
import org.partiql.lang.errors.*
import org.partiql.lang.errors.ErrorCode.*
import org.partiql.lang.errors.Property.*
import org.partiql.lang.syntax.SqlParser.AliasSupportType.*
import org.partiql.lang.syntax.SqlParser.ArgListMode.*
import org.partiql.lang.syntax.SqlParser.ParseType.*
import org.partiql.lang.syntax.TokenType.*
import org.partiql.lang.syntax.TokenType.KEYWORD
import org.partiql.lang.util.*
import java.util.*

/**
 * Parses a list of tokens as infix query expression into a prefix s-expression
 * as the abstract syntax tree.
 */
class SqlParser(private val ion: IonSystem) : Parser {

    private val trueValue: IonBool = ion.newBool(true)

    internal enum class AliasSupportType(val supportsAs: Boolean, val supportsAt: Boolean) {
        NONE(supportsAs = false, supportsAt = false),
        AS_ONLY(supportsAs = true, supportsAt = false),
        AS_AND_AT(supportsAs = true, supportsAt = true)
    }

    internal enum class ArgListMode {
        NORMAL_ARG_LIST,
        STRUCT_LITERAL_ARG_LIST,
        FROM_CLAUSE_ARG_LIST
    }

    internal enum class ParseType(val isJoin: Boolean = false) {
        ATOM,
        CASE_SENSITIVE_ATOM,
        CASE_INSENSITIVE_ATOM,
        PROJECT_ALL,           // Wildcard, i.e. the * in `SELECT * FROM f` and a.b.c.* in `SELECT a.b.c.* FROM f`
        PATH_WILDCARD,
        PATH_UNPIVOT,
        SELECT_LIST,
        SELECT_VALUE,
        DISTINCT,
        INNER_JOIN(isJoin = true),
        LEFT_JOIN(isJoin = true),
        RIGHT_JOIN(isJoin = true),
        OUTER_JOIN(isJoin = true),
        WHERE,
        GROUP,
        GROUP_PARTIAL,
        HAVING,
        LIMIT,
        PIVOT,
        UNPIVOT,
        CALL,
        CALL_AGG,
        CALL_AGG_WILDCARD,
        ARG_LIST,
        AS_ALIAS,
        AT_ALIAS,
        PATH,
        PATH_DOT,
        PATH_SQB, // SQB = SQuare Bracket
        UNARY,
        BINARY,
        TERNARY,
        LIST,
        STRUCT,
        MEMBER,
        CAST,
        TYPE,
        CASE,
        WHEN,
        ELSE,
        BAG;

        val identifier = name.toLowerCase()
    }

    internal data class ParseNode(val type: ParseType,
                                  val token: Token?,
                                  val children: List<ParseNode>,
                                  val remaining: List<Token>) {

        /** Derives a [ParseNode] transforming the list of remaining tokens. */
        private fun derive(tokensHandler: List<Token>.() -> List<Token>): ParseNode =
            copy(remaining = tokensHandler(remaining))

        fun deriveExpected(expectedType: TokenType): ParseNode = derive {
            if (expectedType != this.head?.type) {
                val pvmap = PropertyValueMap()
                pvmap[EXPECTED_TOKEN_TYPE] = expectedType
                this.err("Expected $type", PARSE_EXPECTED_TOKEN_TYPE, pvmap)
            }
            this.tail
        }

        fun deriveExpected(expectedType1: TokenType, expectedType2: TokenType): Pair<ParseNode, Token> =
             if (expectedType1 != this.remaining.head?.type && expectedType2 != this.remaining.head?.type) {
                val pvmap = PropertyValueMap()
                pvmap[EXPECTED_TOKEN_TYPE_1_OF_2] = expectedType1
                pvmap[EXPECTED_TOKEN_TYPE_2_OF_2] = expectedType2
                this.remaining.err("Expected $type", PARSE_EXPECTED_2_TOKEN_TYPES, pvmap)
            } else {
                Pair(copy(remaining = this.remaining.tail), this.remaining.head!!)
            }

        fun deriveExpectedKeyword(keyword: String): ParseNode = derive { tailExpectedKeyword(keyword) }

        val isNumericLiteral = type == ATOM && when (token?.type) {
            LITERAL -> token.value?.isNumeric ?: false
            else -> false
        }

        fun numberValue(): Number = token?.value?.numberValue()
            ?: unsupported("Could not interpret token as number", PARSE_EXPECTED_NUMBER)

        fun unsupported(message: String, errorCode: ErrorCode, errorContext: PropertyValueMap = PropertyValueMap()): Nothing =
            remaining.err(message, errorCode, errorContext)

        fun errMalformedParseTree(message: String): Nothing {
            val context = PropertyValueMap()
            token?.position?.let {
                context[Property.LINE_NUMBER] = it.line
                context[Property.COLUMN_NUMBER] = it.column
            }
            throw ParserException(message, ErrorCode.PARSE_MALFORMED_PARSE_TREE, context)
        }

    }

    private fun Token.toSourceLocation() = SourceLocationMeta(position.line, position.column)

    private fun Token?.toSourceLocationMetaContainer(): MetaContainer =
        if(this == null) {
            metaContainerOf()
        } else {
            metaContainerOf(this.toSourceLocation())
        }

    private fun ParseNode.toSymbolicName(): SymbolicName {
        if(token == null) {
            errMalformedParseTree("Expected ParseNode to have a token")
        }
        when (token.type) {
            LITERAL, IDENTIFIER, QUOTED_IDENTIFIER -> {
                val tokenText = token.text ?: errMalformedParseTree("Expected ParseNode.token to have text")
                return SymbolicName(tokenText, token.toSourceLocationMetaContainer())
            }
            else                                 -> {
                errMalformedParseTree("TokenType.${token.type} cannot be converted to a SymbolicName")
            }
        }
     }

    //***************************************
    // toExprNode
    //***************************************
    private fun ParseNode.toExprNode(): ExprNode {
        val metas = token.toSourceLocationMetaContainer()
        return when (type) {
            ATOM -> when (token?.type) {
                LITERAL, NULL, TRIM_SPECIFICATION, DATE_PART -> {
                    Literal(token.value!!, metas)
                }
                MISSING                                      -> {
                    LiteralMissing(metas)
                }
                QUOTED_IDENTIFIER                            -> {
                    VariableReference(
                        token.text!!,
                        CaseSensitivity.SENSITIVE,
                        metas = metas)
                }
                IDENTIFIER                                   -> {
                    VariableReference(
                        token.text!!,
                        CaseSensitivity.INSENSITIVE,
                        metas = metas)
                }
                else                                         -> {
                    errMalformedParseTree("Unsupported atom token type ${token?.type}")
                }
            }
            LIST -> {
                ListExprNode(children.map { it.toExprNode() }, metas)
            }
            BAG -> {
                Bag(children.map { it.toExprNode() }, metas)
            }
            STRUCT -> {
                val fields = children.map {
                    if (it.type != MEMBER) {
                        errMalformedParseTree("Expected MEMBER node as direct descendant of a STRUCT node but instead found ${it.type}")
                    }
                    if (it.children.size != 2) {
                        errMalformedParseTree("Expected MEMBER node to have 2 children but found ${it.children.size}")
                    }
                    val keyExpr = it.children[0].toExprNode()
                    val valueExpr = it.children[1].toExprNode()
                    StructField(keyExpr, valueExpr)
            }
                Struct(fields, metas)
            }
            UNARY, BINARY, TERNARY -> {
                when(token!!.text) {
                    "is" -> {
                        Typed(
                            TypedOp.IS,
                            children[0].toExprNode(),
                            children[1].toDataType(),
                            metas)
                    }
                    "is_not" -> {
                         NAry(
                             NAryOp.NOT,
                             listOf(Typed(TypedOp.IS, children[0].toExprNode(), children[1].toDataType(), metas)),
                             metas.add(LegacyLogicalNotMeta.instance))
                    }
                    else -> {

                        val (opName, wrapInNot) = when (token.text) {
                            "not_between" -> Pair("between", true)
                            "not_like" -> Pair("like", true)
                            "not_in" -> Pair("in", true)
                            else -> Pair(token.text!!, false)
                        }

                        when (opName) {
                            "@"  -> {
                                val chlidNode = children[0]
                                val childToken = chlidNode.token ?: errMalformedParseTree("@ node does not have a token")
                                when(childToken.type) {
                                    QUOTED_IDENTIFIER                            -> {
                                        VariableReference(
                                            chlidNode.token.text!!,
                                            CaseSensitivity.SENSITIVE,
                                            ScopeQualifier.LEXICAL,
                                            childToken.toSourceLocationMetaContainer())
                                    }
                                    IDENTIFIER                                   -> {
                                        VariableReference(
                                            chlidNode.token.text!!,
                                            CaseSensitivity.INSENSITIVE,
                                            ScopeQualifier.LEXICAL,
                                            childToken.toSourceLocationMetaContainer())
                                    }
                                    else                                         -> {
                                        errMalformedParseTree("Unexpected child node token type of @ operator node ${childToken}")
                                    }
                                }
                            }
                            else -> {
                                val op = NAryOp.forSymbol(opName) ?: errMalformedParseTree("Unsupported operator: $opName")

                                val exprNode = NAry(op, children.map { it.toExprNode() }, metas)
                                if (!wrapInNot) {
                                    exprNode
                                } else {
                                    NAry(
                                        NAryOp.NOT,
                                        listOf(exprNode),
                                        metas.add(LegacyLogicalNotMeta.instance))
                                }
                            }
                        }
                    }
                }

            }
            CAST -> {
                val funcExpr = children[0].toExprNode()
                val dataType = children[1].toDataType()
                Typed(TypedOp.CAST, funcExpr, dataType, metas)
            }
            CALL -> {
                // Note:  we are forcing all function name lookups to be case insensitive here...
                // This seems like the right thing to do because that is consistent with the
                // previous behavior.
                val funcExpr =
                    VariableReference(
                        token?.text!!.toLowerCase(),
                        CaseSensitivity.INSENSITIVE,
                        metas = metaContainerOf())

                NAry(NAryOp.CALL, listOf(funcExpr) + children.map { it.toExprNode() }, metas)
            }
            CALL_AGG -> {
                val funcExpr =
                    VariableReference(
                        token?.text!!.toLowerCase(),
                        CaseSensitivity.INSENSITIVE,
                        metas = metaContainerOf())

                CallAgg(funcExpr, SetQuantifier.ALL, children.first().toExprNode(), metas)
            }
            CALL_AGG_WILDCARD -> {
                if(token!!.type != KEYWORD || token.keywordText != "count") {
                    errMalformedParseTree("only COUNT can be used with a wildcard")
                }
                val countStar = createCountStar(ion, metas)
                countStar
            }
            PATH -> {
                val rootExpr = children[0].toExprNode()
                val pathComponents = children.drop(1).map {
                    when(it.type) {
                        PATH_DOT -> {
                            if(it.children.size != 1) {
                                errMalformedParseTree("Unexpected number of child elements in PATH_DOT ParseNode")
                            }
                            val atomParseNode = it.children.first()
                            val atomMetas = atomParseNode.token.toSourceLocationMetaContainer()
                            when(atomParseNode.type) {
                                CASE_SENSITIVE_ATOM, CASE_INSENSITIVE_ATOM -> {
                                    val sensitivity = if(atomParseNode.type  == CASE_SENSITIVE_ATOM)
                                        CaseSensitivity.SENSITIVE
                                    else {
                                        CaseSensitivity.INSENSITIVE
                                    }
                                    PathComponentExpr(
                                        Literal(
                                            ion.newString(atomParseNode.token?.text!!),
                                            atomMetas),
                                        sensitivity)
                                }
                                PATH_UNPIVOT -> {
                                    PathComponentUnpivot(atomMetas)
                                }
                                else -> errMalformedParseTree("Unsupported child path node of PATH_DOT")
                            }
                        }
                        PATH_SQB -> {
                            if(it.children.size != 1) {
                                errMalformedParseTree("Unexpected number of child elements in PATH_SQB ParseNode")
                            }
                            val child = it.children.first()
                            val childMetas = child.token.toSourceLocationMetaContainer()
                            if(child.type == PATH_WILDCARD) {
                                PathComponentWildcard(childMetas)
                            } else {
                                PathComponentExpr(child.toExprNode(), CaseSensitivity.SENSITIVE)
                            }
                        }
                        else -> {
                            errMalformedParseTree("Unsupported path component: ${it.type}")
                        }
                    }//.copy(token.toSourceLocationMetaContainer())
                }
                Path(rootExpr, pathComponents, metas)
            }
            CASE -> {
                when (children.size) {
                    // Searched CASE
                    1 -> {
                        val branches = ArrayList<SearchedCaseWhen>()
                        var elseExpr: ExprNode? = null
                        children[0].children.forEach {
                            when(it.type) {
                                WHEN -> branches.add(
                                    SearchedCaseWhen(
                                        it.children[0].toExprNode(),
                                        it.children[1].toExprNode()))

                                ELSE -> elseExpr = it.children[0].toExprNode()
                                else -> errMalformedParseTree("CASE clause must be WHEN or ELSE")
                            }
                        }

                        SearchedCase(branches, elseExpr, metas)
                    }
                    // Simple CASE
                    2 -> {
                        val valueExpr = children[0].toExprNode()
                        val branches = ArrayList<SimpleCaseWhen>()
                        var elseExpr: ExprNode? = null
                        children[1].children.forEach {
                            when(it.type) {
                                WHEN -> branches.add(
                                    SimpleCaseWhen(
                                        it.children[0].toExprNode(),
                                        it.children[1].toExprNode()))

                                ELSE -> elseExpr = it.children[0].toExprNode()
                                else -> errMalformedParseTree("CASE clause must be WHEN or ELSE")
                            }
                        }

                        SimpleCase(valueExpr, branches, elseExpr, metas)
                    }
                    else -> errMalformedParseTree("CASE must be searched or simple")
                }
            }
            SELECT_LIST, SELECT_VALUE, PIVOT -> {
                // The first child of a SELECT_LIST parse node and be either DISTINCT or ARG_LIST.
                // If it is ARG_LIST, the children of that node are the select items and the SetQuantifier is ALL
                // If it is DISTINCT, the SetQuantifier is DISTINCT and there should be one child node, an ARG_LIST
                // containing the select items.

                // The second child of a SELECT_LIST is always an ARG_LIST containing the from clause.

                // GROUP BY, GROUP PARTIAL BY, WHERE, HAVING and limit parse nodes each have distinct ParseNodeTypes
                // and if present, exist in children, starting at the third position.

                var setQuantifier = SetQuantifier.ALL
                var selectList = children[0]
                val fromList = children[1]

                // We will remove items from this collection as we consume them.
                // If any unconsumed children remain, we've missed something and should throw an exception.
                val unconsumedChildren = children.drop(2).toMutableList()

                // If the query parsed was a `SELECT DISTINCT ...`, children[0] is of type DISTINCT and its
                // children are the actual select list.
                if(selectList.type == DISTINCT) {
                    selectList = selectList.children[0]
                    setQuantifier = SetQuantifier.DISTINCT
                }

                val projection = when(type) {
                    SELECT_LIST -> {
                        val selectListItems = selectList.children.map { it.toSelectListItem() }
                        SelectProjectionList(selectListItems)
                    }
                    SELECT_VALUE -> {
                        SelectProjectionValue(selectList.toExprNode())
                    }
                    PIVOT -> {
                        val member = children[0]
                        val asExpr = member.children[0].toExprNode()
                        val atExpr = member.children[1].toExprNode()
                        SelectProjectionPivot(asExpr, atExpr)
                    }
                    else -> {
                        throw IllegalStateException("This can never happen!")
                    }
                }

                if(fromList.type != ARG_LIST) {
                    errMalformedParseTree("Invalid second child of SELECT_LIST")
                }

                val fromSource = fromList.children.toFromSource()

                val whereExpr = unconsumedChildren.firstOrNull { it.type == WHERE }?.let {
                    unconsumedChildren.remove(it)
                    it.children[0].toExprNode()
                }

                val groupBy = unconsumedChildren.firstOrNull { it.type == GROUP || it.type == GROUP_PARTIAL }?.let {
                    unconsumedChildren.remove(it)
                    val groupingStrategy = when(it.type) {
                        GROUP -> GroupingStrategy.FULL
                        else -> GroupingStrategy.PARTIAL
                    }

                    val groupAsName = if(it.children.size > 1) {
                        it.children[1].toSymbolicName()
                    } else {
                        null
                    }

                    GroupBy(
                        groupingStrategy,
                        it.children[0].children.map {
                            val (alias, groupByItemNode) = it.unwrapAsAlias()
                            GroupByItem(
                                groupByItemNode.toExprNode(),
                                alias)
                        },
                        groupAsName)
                }

                val havingExpr = unconsumedChildren.firstOrNull { it.type == HAVING }?.let {
                    unconsumedChildren.remove(it)
                    it.children[0].toExprNode()
                }

                val limitExpr = unconsumedChildren.firstOrNull { it.type == LIMIT }?.let {
                    unconsumedChildren.remove(it)
                    it.children[0].toExprNode()
                }

                if(!unconsumedChildren.isEmpty()) {
                    errMalformedParseTree("Unprocessed query components remaining")
                }

                Select(
                    setQuantifier = setQuantifier,
                    projection = projection,
                    from = fromSource,
                    where = whereExpr,
                    groupBy = groupBy,
                    having = havingExpr,
                    limit = limitExpr,
                    metas = metas)
            }
            else -> unsupported("Unsupported syntax for $type", PARSE_UNSUPPORTED_SYNTAX)
        }
    }

    private data class AsAlias(val name: SymbolicName?, val node: ParseNode)

    /**
     * Unwraps select list items that have been wrapped in an annotating node containing the `AS <alias>`,
     * if present.
     */
    private fun ParseNode.unwrapAsAlias(): AsAlias =
        if(type == AS_ALIAS) {
            AsAlias(SymbolicName(token!!.text!!, token.toSourceLocationMetaContainer()), children[0])
        } else {
            AsAlias(null, this)
        }

    private fun ParseNode.toSelectListItem(): SelectListItem {
        val metas = token.toSourceLocationMetaContainer()
        return when (type) {
            PROJECT_ALL -> {
                if (children.isEmpty()) {
                    SelectListItemStar(metas)
                }
                else {
                    val expr = children[0].toExprNode()
                    SelectListItemProjectAll(expr)
                }

            }
            else        -> {
                val (asAliasSymbol, parseNode) = unwrapAsAlias()
                SelectListItemExpr(parseNode.toExprNode(), asAliasSymbol)
            }
        }
    }

    private data class FromSourceAlias(val asAlias: SymbolicName?, val atAlias: SymbolicName?, val node: ParseNode)

    private fun ParseNode.unwrapFromSourceAlias(): FromSourceAlias {
        val metas = token.toSourceLocationMetaContainer()
        return when (type) {
            AT_ALIAS -> {
                val atAlias = SymbolicName(token!!.text!!, metas)
                if(children[0].type != AS_ALIAS) {
                    FromSourceAlias(null, atAlias, children[0])
                } else {
                    val asAlias = SymbolicName(children[0].token!!.text!!, metas)
                    FromSourceAlias(asAlias, atAlias, children[0].children[0])
                }
            }
            AS_ALIAS -> {
                val asAlias = SymbolicName(token!!.text!!, metas)
                FromSourceAlias(asAlias, null, children[0])
            }
            else     -> {
                FromSourceAlias(null, null, this)
            }
        }
    }

    private fun List<ParseNode>.toFromSource(): FromSource {
        val left = this[0].toFromSource()
        if(size == 1) {
            return left
        }

        return this.toFromSourceWithJoin(1, left)
    }

    private fun ParseNode.toFromSource(): FromSource {
        val (asAliasSymbol, atAliasSymbol, parseNode) = unwrapFromSourceAlias()
        return when (parseNode.type) {
            UNPIVOT -> {
                val expr = parseNode.children[0].toExprNode()
                FromSourceUnpivot(expr, asAliasSymbol, atAliasSymbol, parseNode.token.toSourceLocationMetaContainer())
            }
            else    -> {
                FromSourceExpr(parseNode.toExprNode(), asAliasSymbol, atAliasSymbol)
            }
        }
    }

    private fun List<ParseNode>.toFromSourceWithJoin(currentIndex: Int, left: FromSource): FromSource {
        val joinOp = when(this[currentIndex].type) {
            INNER_JOIN -> JoinOp.INNER
            LEFT_JOIN -> JoinOp.LEFT
            RIGHT_JOIN -> JoinOp.RIGHT
            OUTER_JOIN -> JoinOp.OUTER
            else -> { this[currentIndex].errMalformedParseTree("Unsupported syntax for ${this[currentIndex].type}") }
        }

        val right = this[currentIndex].children[0].toFromSource()
        val (isImplicitJoin, condition) = when {
            this[currentIndex].children.size > 1 -> Pair(false, this[currentIndex].children[1].toExprNode())
            else                                 -> Pair(true, Literal(trueValue, metaContainerOf()))
        }

        val fromSourceJoin = FromSourceJoin(
            joinOp,
            left,
            right,
            condition,
            this[currentIndex].token.toSourceLocationMetaContainer().let {
                when {
                    isImplicitJoin -> it.add(IsImplictJoinMeta.instance)
                    else           -> it
                }
            })


        val nextIndex = currentIndex + 1
        return if(size - 1 < nextIndex) {
            fromSourceJoin
        } else {
            this.toFromSourceWithJoin(nextIndex, fromSourceJoin)
        }
    }

    private fun ParseNode.toDataType(): DataType {
        if(type != TYPE) {
            errMalformedParseTree("Expected ParseType.TYPE instead of $type")
        }
        if(children.size > 1) {
            errMalformedParseTree("Apparently DataType needs multiple lengths, sizes, etc")
        }
        val sqlDataType = SqlDataType.forTypeName(token!!.keywordText!!)
        if(sqlDataType == null) {
            errMalformedParseTree("Invalid DataType: ${token.keywordText!!}")
        }

        return DataType(
            sqlDataType,
            children.mapNotNull { it.token?.value?.longValue() },
            metas = token.toSourceLocationMetaContainer())
    }

    /**********************************************************************************************
     * Parse logic below this line.
     **********************************************************************************************/

    // keywords that IN (<expr>) evaluate more like grouping than a singleton in value list
    private val IN_OP_NORMAL_EVAL_KEYWORDS = setOf("select", "values")

    /**
     * Parses the given token list.
     *
     * @param precedence The precedence of the current expression parsing.
     *                   A negative value represents the "top-level" parsing.
     *
     * @return The parse tree for the given expression.
     */
    internal fun List<Token>.parseExpression(precedence: Int = -1): ParseNode {
        var expr = parseUnaryTerm()
        var rem = expr.remaining

        fun headPrecedence() = rem.head?.infixPrecedence ?: 0

        // XXX this is a Pratt Top-Down Operator Precedence implementation
        while (!rem.isEmpty() && precedence < headPrecedence()) {
            val op = rem.head!!
            if (!op.isBinaryOperator && op.keywordText !in SPECIAL_INFIX_OPERATORS) {
                // unrecognized operator
                break
            }

            fun parseRightExpr() = if (rem.size < 3) {
                rem.err(
                    "Missing right-hand side expression of infix operator",
                    PARSE_EXPECTED_EXPRESSION
                )
            } else {
                rem.tail.parseExpression(
                    precedence = op.infixPrecedence
                )
            }

            val right = when (op.keywordText) {
                // IS/IS NOT requires a type
                "is", "is_not" -> rem.tail.parseType()
                // IN has context sensitive parsing rules around parenthesis
                "in", "not_in" -> when {
                    rem.tail.head?.type == LEFT_PAREN
                            && rem.tail.tail.head?.keywordText !in IN_OP_NORMAL_EVAL_KEYWORDS ->
                        rem.tail.tail.parseArgList(
                            aliasSupportType = NONE,
                            mode = NORMAL_ARG_LIST
                        ).deriveExpected(RIGHT_PAREN).copy(LIST)
                    else -> parseRightExpr()
                }
                else -> parseRightExpr()
            }
            rem = right.remaining

            expr = when {
                op.isBinaryOperator -> ParseNode(BINARY, op, listOf(expr, right), rem)
                else -> when (op.keywordText) {
                    "between", "not_between" -> {
                        val rest = rem.tailExpectedKeyword("and")
                        if (rest.onlyEndOfStatement()) {
                            rem.head.err("Expected expression after AND", PARSE_EXPECTED_EXPRESSION)
                        } else {
                            rem = rest
                            val third = rem.parseExpression(
                                precedence = op.infixPrecedence
                            )
                            rem = third.remaining
                            ParseNode(TERNARY, op, listOf(expr, right, third), rem)
                        }
                    }
                    "like", "not_like" -> {
                        when {
                            rem.head?.keywordText == "escape" -> {
                                val rest = rem.tailExpectedKeyword("escape")
                                if (rest.onlyEndOfStatement()) {
                                    rem.head.err("Expected expression after ESCAPE", PARSE_EXPECTED_EXPRESSION)
                                } else {
                                    rem = rest
                                    val third = rem.parseExpression(precedence = op.infixPrecedence)
                                    rem = third.remaining
                                    ParseNode(TERNARY, op, listOf(expr, right, third), rem)
                                }
                            }
                            else -> ParseNode(BINARY, op, listOf(expr, right), rem)
                        }
                    }
                    else -> rem.err("Unknown infix operator", PARSE_UNKNOWN_OPERATOR)
                }
            }
        }
        return expr
    }

    private fun List<Token>.parseUnaryTerm(): ParseNode =
        when (head?.isUnaryOperator) {
            true -> {
                val op = head!!

                val term = tail.parseUnaryTerm()
                var expr: ParseNode? = null

                // constant fold unary plus/minus into constant literals
                when (op.keywordText) {
                    "+" -> when {
                        term.isNumericLiteral -> {
                            // unary plus is a NO-OP
                            expr = term
                        }
                    }
                    "-" -> when {
                        term.isNumericLiteral -> {
                            val num = -term.numberValue()
                            expr = ParseNode(ATOM,
                                             term.token!!.copy(value = num.ionValue(ion)),
                                             emptyList(),
                                             term.remaining)
                        }
                    }
                    "not" -> {
                        val children = tail.parseExpression(op.prefixPrecedence)
                        expr = ParseNode(UNARY, op, listOf(children), children.remaining)
                    }
                }

                expr ?: ParseNode(UNARY, op, listOf(term), term.remaining)
            }
            else -> parsePathTerm()
        }

    private fun List<Token>.parsePathTerm(): ParseNode {
        val term = parseTerm()
        val path = ArrayList<ParseNode>(listOf(term))
        var rem = term.remaining
        var hasPath = true
        while (hasPath) {
            when (rem.head?.type) {
                DOT -> {
                    val dotToken = rem.head!!
                    // consume first dot
                    rem = rem.tail
                    val pathPart = when (rem.head?.type) {
                        IDENTIFIER        -> {
                            val litToken = Token(LITERAL, ion.newString(rem.head?.text!!), rem.head!!.position)
                            ParseNode(CASE_INSENSITIVE_ATOM, litToken, emptyList(), rem.tail)
                        }
                        QUOTED_IDENTIFIER -> {
                            val litToken = Token(LITERAL, ion.newString(rem.head?.text!!), rem.head!!.position)
                            ParseNode(CASE_SENSITIVE_ATOM, litToken, emptyList(), rem.tail)
                        }
                        STAR              -> {
                            ParseNode(PATH_UNPIVOT, rem.head, emptyList(), rem.tail)
                        }
                        else              -> {
                            rem.err("Invalid path dot component", PARSE_INVALID_PATH_COMPONENT)
                        }
                    }
                    path.add(ParseNode(PATH_DOT, dotToken, listOf(pathPart), rem))
                    rem = rem.tail
                }
                LEFT_BRACKET -> {
                    val leftBracketToken = rem.head!!
                    rem = rem.tail
                    val expr = when (rem.head?.type) {
                        STAR -> ParseNode(PATH_WILDCARD, rem.head, emptyList(), rem.tail)
                        else -> rem.parseExpression()
                    }.deriveExpected(RIGHT_BRACKET)
                    path.add(ParseNode(PATH_SQB, leftBracketToken, listOf(expr), rem.tail))
                    rem = expr.remaining
                }
                else -> hasPath = false
            }
        }

        return when (path.size) {
            1 -> term
            else -> ParseNode(PATH, null, path, rem)
        }
    }

    private fun List<Token>.parseTerm(): ParseNode = when (head?.type) {
        OPERATOR -> when (head?.keywordText) {
        // the lexical scope operator is **only** allowed with identifiers
            "@" -> when (tail.head?.type) {
                IDENTIFIER, QUOTED_IDENTIFIER -> ParseNode(
                    UNARY,
                    head,
                    listOf(tail.atomFromHead()),
                    tail.tail
                )
                else -> err("Identifier must follow @-operator", PARSE_MISSING_IDENT_AFTER_AT)
            }
            else -> err("Unexpected operator", PARSE_UNEXPECTED_OPERATOR)
        }

        KEYWORD -> when (head?.keywordText) {
            "case" -> when (tail.head?.keywordText) {
                "when" -> tail.parseCase(isSimple = false)
                else -> tail.parseCase(isSimple = true)
            }
            "cast" -> tail.parseCast()
            "select" -> tail.parseSelect()
            "pivot" -> tail.parsePivot()
            // table value constructor--which aliases to bag constructor in PartiQL with very
            // specific syntax
            "values" -> tail.parseTableValues().copy(type = BAG)
            "substring" -> tail.parseSubstring(head!!)
            "trim" -> tail.parseTrim(head!!)
            "extract" -> tail.parseExtract(head!!)
            in FUNCTION_NAME_KEYWORDS -> when (tail.head?.type) {
                LEFT_PAREN ->
                    tail.tail.parseFunctionCall(head!!)
                else -> err("Unexpected keyword", PARSE_UNEXPECTED_KEYWORD)
            }
            else -> err("Unexpected keyword", PARSE_UNEXPECTED_KEYWORD)
        }
        LEFT_PAREN -> {
            val group = tail.parseArgList(
                aliasSupportType = NONE,
                mode = NORMAL_ARG_LIST
            ).deriveExpected(RIGHT_PAREN)
            when (group.children.size) {
                0 -> tail.err("Expression group cannot be empty", PARSE_EXPECTED_EXPRESSION)
            // expression grouping
                1 -> group.children[0].copy(remaining = group.remaining)
            // row value constructor--which aliases to list constructor in PartiQL
                else -> group.copy(type = LIST)
            }
        }
        LEFT_BRACKET -> when (tail.head?.type) {
            RIGHT_BRACKET -> ParseNode(LIST, null, emptyList(), tail.tail)
            else -> tail.parseListLiteral()
        }
        LEFT_DOUBLE_ANGLE_BRACKET -> when (tail.head?.type) {
            RIGHT_DOUBLE_ANGLE_BRACKET -> ParseNode(BAG, null, emptyList(), tail.tail)
            else -> tail.parseBagLiteral()
        }
        LEFT_CURLY -> when (tail.head?.type) {
            RIGHT_CURLY -> ParseNode(STRUCT, null, emptyList(), tail.tail)
            else -> tail.parseStructLiteral()
        }
        IDENTIFIER, QUOTED_IDENTIFIER -> when (tail.head?.type) {
            LEFT_PAREN -> tail.tail.parseFunctionCall(head!!)
            else -> atomFromHead()
        }
        LITERAL, NULL, MISSING, TRIM_SPECIFICATION, DATE_PART -> atomFromHead()
        else -> err("Unexpected term", PARSE_UNEXPECTED_TERM)
    }

    private fun List<Token>.parseCase(isSimple: Boolean): ParseNode {
        var rem = this
        val children = ArrayList<ParseNode>()
        if (isSimple) {
            val valueExpr = parseExpression()
            children.add(valueExpr)
            rem = valueExpr.remaining
        }

        val caseBody = rem.parseCaseBody()
        children.add(caseBody)
        rem = caseBody.remaining

        return ParseNode(CASE, null, children, rem)
    }

    private fun List<Token>.parseCaseBody(): ParseNode {
        val children = ArrayList<ParseNode>()
        var rem = this
        while (rem.head?.keywordText == "when") {
            val conditionExpr = rem.tail.parseExpression().deriveExpectedKeyword("then")
            rem = conditionExpr.remaining

            val result = rem.parseExpression()
            rem = result.remaining

            children.add(ParseNode(WHEN, null, listOf(conditionExpr, result), rem))
        }
        if (children.isEmpty()) {
            err("Expected a WHEN clause in CASE", PARSE_EXPECTED_WHEN_CLAUSE)
        }
        if (rem.head?.keywordText == "else") {
            val elseExpr = rem.tail.parseExpression()
            rem = elseExpr.remaining

            children.add(ParseNode(ELSE, null, listOf(elseExpr), rem))
        }

        return ParseNode(ARG_LIST, null, children, rem)
            .deriveExpectedKeyword("end")
    }

    private fun List<Token>.parseCast(): ParseNode {
        if (head?.type != LEFT_PAREN) {
            err("Missing left parenthesis after CAST", PARSE_EXPECTED_LEFT_PAREN_AFTER_CAST)
        }
        val valueExpr = tail.parseExpression().deriveExpected(AS)
        var rem = valueExpr.remaining

        val typeNode = rem.parseType().deriveExpected(RIGHT_PAREN)
        rem = typeNode.remaining

        return ParseNode(CAST, head, listOf(valueExpr, typeNode), rem)
    }

    private fun List<Token>.parseType(): ParseNode {
        val typeName = head?.keywordText
        val typeArity = TYPE_NAME_ARITY_MAP[typeName] ?: err("Expected type name", PARSE_EXPECTED_TYPE_NAME)

        val typeNode = when (tail.head?.type) {
            LEFT_PAREN -> tail.tail.parseArgList(
                aliasSupportType = NONE,
                mode = NORMAL_ARG_LIST
            ).copy(
                type = TYPE,
                token = head
            ).deriveExpected(RIGHT_PAREN)

            else -> ParseNode(TYPE, head, emptyList(), tail)
        }
        if (typeNode.children.size !in typeArity) {
            val pvmap = PropertyValueMap()
            pvmap[CAST_TO] = typeName?: ""
            pvmap[EXPECTED_ARITY_MIN] = typeArity.first
            pvmap[EXPECTED_ARITY_MAX] = typeArity.last
            tail.err("CAST for $typeName must have arity of $typeArity", PARSE_CAST_ARITY, pvmap)
        }
        for (child in typeNode.children) {
            if (child.type != ATOM
                || child.token?.type != LITERAL
                || child.token.value?.isUnsignedInteger != true) {
                err("Type parameter must be an unsigned integer literal", PARSE_INVALID_TYPE_PARAM)
            }
        }

        return typeNode
    }

    private fun List<Token>.parsePivot(): ParseNode {
        var rem = this
        val value = rem.parseExpression().deriveExpectedKeyword("at")
        rem = value.remaining
        val name = rem.parseExpression()
        rem = name.remaining
        val selectAfterProjection = parseSelectAfterProjection(PIVOT,ParseNode(MEMBER, null, listOf(name, value), rem))
        return selectAfterProjection
    }

    private fun List<Token>.parseSelect(): ParseNode {
        var rem = this
        val distinct = when (head?.keywordText) {
            "distinct" -> {
                rem = tail
                true
            }
            "all" -> {
                // SELECT ALL is default semantics
                rem = tail
                false
            }
            else -> false
        }

        var type = SELECT_LIST
        var projection = when {
            rem.head?.keywordText == "value" -> {
                type = SELECT_VALUE
                rem.tail.parseExpression()
            }
            else -> {
                val list = rem.parseSelectList()
                if (list.children.isEmpty()) {
                    rem.err("Cannot have empty SELECT list", PARSE_EMPTY_SELECT)
                }

                val asterisk = list.children.firstOrNull { it.type == ParseType.PROJECT_ALL && it.children.isEmpty() }
                if(asterisk != null
                   && list.children.size > 1) {
                    asterisk.token.err(
                        "Other expressions may not be present in the select list when '*' is used without dot notation.",
                        ErrorCode.PARSE_ASTERISK_IS_NOT_ALONE_IN_SELECT_LIST)
                }

                list
            }
        }
        if (distinct) {
            projection = ParseNode(DISTINCT, null, listOf(projection), projection.remaining)
        }

        val parseSelectAfterProjection = parseSelectAfterProjection(type, projection)
        return parseSelectAfterProjection
    }

    /**
     * Inspects a path expression to determine if should be treated as a regular [ParseType.PATH] expression or
     * converted to a [ParseType.PROJECT_ALL].
     *
     * Examples of expressions that are converted to [ParseType.PROJECT_ALL] are:
     *
     * ```sql
     *      SELECT * FROM foo
     *      SELECT foo.* FROM foo
     *      SELECT f.* FROM foo as f
     *      SELECT foo.bar.* FROM foo
     *      SELECT f.bar.* FROM foo as f
     * ```
     * Also validates that the expression is valid for select list context.  It does this by making
     * sure that expressions looking like the following do not appear:
     *
     * ```sql
     *      SELECT foo[*] FROM foo
     *      SELECT f.*.bar FROM foo as f
     *      SELECT foo[1].* FROM foo
     *      SELECT foo.*.bar FROM foo
     * ```
     *
     * If no conversion is needed, returns the original `pathNode`.
     * If conversion is needed, clones the original `pathNode`, changing the `type` to `PROJECT_ALL`,
     * removes the trailing `PATH_WILDCARD_UNPIVOT` and returns.
     */
    private fun inspectPathExpression(pathNode: ParseNode): ParseNode {
        fun flattenParseNode(node: ParseNode): List<ParseNode> {
            fun doFlatten(n: ParseNode, l: MutableList<ParseNode>) {
                l.add(n)
                n.children.forEach { doFlatten(it,l ) }
            }
            val list = mutableListOf<ParseNode>()
            doFlatten(node, list)
            return list
        }

        val flattened = flattenParseNode(pathNode).drop(2)

        //Is invalid if contains PATH_WILDCARD (i.e. to `[*]`}
        flattened.firstOrNull { it.type == PATH_WILDCARD }
            ?.token
            ?.err("Invalid use of * in select list", ErrorCode.PARSE_INVALID_CONTEXT_FOR_WILDCARD_IN_SELECT_LIST)

        //Is invalid if contains PATH_WILDCARD_UNPIVOT (i.e. * as part of a dotted expression) anywhere except at the end.
        //i.e. f.*.b is invalid but f.b.* is not.
        flattened.dropLast(1).firstOrNull { it.type == PATH_UNPIVOT }
            ?.token
            ?.err("Invalid use of * in select list", ErrorCode.PARSE_INVALID_CONTEXT_FOR_WILDCARD_IN_SELECT_LIST)

        //If the last path component expression is a *, then the PathType is a wildcard and we need to do one
        //additional check.
        if(flattened.last().type == ParseType.PATH_UNPIVOT) {

            //Is invalid if contains a square bracket anywhere and a wildcard at the end.
            //i.e f[1].* is invalid
            flattened.firstOrNull { it.type == PATH_SQB }
                ?.token
                ?.err("Cannot use [] and * together in SELECT list expression", ErrorCode.PARSE_CANNOT_MIX_SQB_AND_WILDCARD_IN_SELECT_LIST)

            val pathPart = pathNode.copy(children = pathNode.children.dropLast(1))

            return ParseNode(
                type = PROJECT_ALL,
                token = null,
                children = listOf(if (pathPart.children.size == 1) pathPart.children[0] else pathPart),
                remaining = pathNode.remaining)
        }
        return pathNode
    }


    private fun List<Token>.parseSelectList(): ParseNode {
        return parseCommaList {
            if (this.head?.type == STAR) {
                ParseNode(PROJECT_ALL, this.head, listOf(), this.tail)
            }
            else {
                val expr = parseExpression().let {
                    when (it.type) {
                        PATH -> inspectPathExpression(it)
                        else -> it
                    }
                }

                var rem = expr.remaining
                val aliasName = when {
                    rem.head?.type == AS                    -> {
                        rem = rem.tail
                        val name = rem.head
                        if (name == null || !name.type.isIdentifier()) {
                            rem.err("Expected identifier for alias", PARSE_EXPECTED_IDENT_FOR_ALIAS)
                        }
                        rem = rem.tail
                        name
                    }
                    rem.head?.type?.isIdentifier() ?: false -> {
                        val name = rem.head
                        rem = rem.tail
                        name
                    }
                    else                                    -> null
                }

                if (aliasName != null) {
                    ParseNode(AS_ALIAS, aliasName, listOf(expr), rem)
                }
                else {
                    expr
                }
            }
        }
    }

    private fun parseSelectAfterProjection(selectType: ParseType, projection: ParseNode): ParseNode {
        val children = ArrayList<ParseNode>()
        var rem = projection.remaining
        children.add(projection)

        // TODO support SELECT with no FROM
        if (rem.head?.keywordText != "from") {
            rem.err("Expected FROM after SELECT list", PARSE_SELECT_MISSING_FROM)
        }

        val fromList = rem.tail.parseArgList(
            aliasSupportType = AS_AND_AT,
            mode = FROM_CLAUSE_ARG_LIST
        )

        rem = fromList.remaining
        children.add(fromList)

        fun parseOptionalSingleExpressionClause(type: ParseType) {
            if (rem.head?.keywordText == type.identifier) {
                val expr = rem.tail.parseExpression()
                rem = expr.remaining
                children.add(ParseNode(type, null, listOf(expr), rem))
            }
        }

        parseOptionalSingleExpressionClause(WHERE)

        if (rem.head?.keywordText == "group") {
            rem = rem.tail
            val type = when (rem.head?.keywordText) {
                "partial" -> {
                    rem = rem.tail
                    GROUP_PARTIAL
                }
                else -> GROUP
            }

            val groupChildren = ArrayList<ParseNode>()

            rem = rem.tailExpectedKeyword("by")

            val groupKey = rem.parseArgList(
                aliasSupportType = AS_ONLY,
                mode = NORMAL_ARG_LIST
            )
            groupKey.children.forEach {
                // TODO support ordinal case
                if (it.token?.type == LITERAL) {
                    it.token.err("Literals (including ordinals) not supported in GROUP BY", PARSE_UNSUPPORTED_LITERALS_GROUPBY)
                }
            }
            groupChildren.add(groupKey)
            rem = groupKey.remaining

            if (rem.head?.keywordText == "group") {
                rem = rem.tail.tailExpectedKeyword("as")

                if (rem.head?.type?.isIdentifier() != true) {
                    rem.err("Expected identifier for GROUP name", PARSE_EXPECTED_IDENT_FOR_GROUP_NAME)
                }
                groupChildren.add(rem.atomFromHead())
                rem = rem.tail
            }
            children.add(
                ParseNode(
                    type,
                    null,
                    groupChildren,
                    rem
                )
            )
        }

        parseOptionalSingleExpressionClause(HAVING)

        parseOptionalSingleExpressionClause(LIMIT)

        return ParseNode(selectType, null, children, rem)
    }

    private fun List<Token>.parseFunctionCall(name: Token): ParseNode {
        val nameText = name.text!!
        var callType = when {
            // TODO make this injectable
            nameText in STANDARD_AGGREGATE_FUNCTIONS -> CALL_AGG
            else -> CALL
        }

        // TODO https://github.com/partiql/partiql-lang-kotlin/issues/38 support DISTINCT/ALL syntax

        val call =  when (head?.type) {
            RIGHT_PAREN -> ParseNode(callType, name, emptyList(), tail)
            STAR -> {
                // support for special form COUNT(*)
                callType = CALL_AGG_WILDCARD
                if (nameText != "count") {
                    err("$nameText(*) is not allowed", PARSE_UNSUPPORTED_CALL_WITH_STAR)
                }
                ParseNode(
                    callType,
                    name,
                    emptyList(),
                    tail
                ).deriveExpected(RIGHT_PAREN)
            }
            else -> {
                parseArgList(
                    aliasSupportType = NONE,
                    mode = NORMAL_ARG_LIST
                ).copy(
                    type = callType,
                    token = name
                ).deriveExpected(RIGHT_PAREN)
            }
        }

        if (callType == CALL_AGG && call.children.size != 1) {
            err("Aggregate functions are always unary", PARSE_NON_UNARY_AGREGATE_FUNCTION_CALL)
        }

        return call
    }

    /**
     * Parses substring
     *
     * Syntax is either SUBSTRING(<str> FROM <start position> [FOR <string length>])
     * or SUBSTRING(<str>, <start position> [, <string length>])
     */
    private fun List<Token>.parseSubstring(name: Token): ParseNode {
        var rem = this

        if (rem.head?.type != LEFT_PAREN) {
            val pvmap = PropertyValueMap()
            pvmap[EXPECTED_TOKEN_TYPE] = LEFT_PAREN
            rem.err("Expected $LEFT_PAREN", PARSE_EXPECTED_LEFT_PAREN_BUILTIN_FUNCTION_CALL, pvmap)
        }

        var stringExpr = tail.parseExpression()
        rem = stringExpr.remaining
        var parseSql92Syntax = false

        stringExpr = when {
            rem.head!!.keywordText == "from" -> {
                parseSql92Syntax = true
                stringExpr.deriveExpectedKeyword("from")
            }
            rem.head!!.type == COMMA -> stringExpr.deriveExpected(COMMA)
            else -> rem.err("Expected $KEYWORD 'from' OR $COMMA", PARSE_EXPECTED_ARGUMENT_DELIMITER)
        }

        val (positionExpr: ParseNode, expectedToken: Token) = stringExpr.remaining.parseExpression()
                .deriveExpected(if(parseSql92Syntax) FOR else COMMA, RIGHT_PAREN)

        if (expectedToken.type == RIGHT_PAREN) {
            return ParseNode(
                ParseType.CALL,
                name,
                listOf(stringExpr, positionExpr),
                positionExpr.remaining
            )
        }

        rem = positionExpr.remaining
        val lengthExpr = rem.parseExpression().deriveExpected(RIGHT_PAREN)
        return ParseNode(ParseType.CALL,
                name,
                listOf(stringExpr, positionExpr, lengthExpr),
                lengthExpr.remaining)

    }

    /**
     * Parses trim
     *
     * Syntax is TRIM([[ specification ] [to trim characters] FROM] <trim source>).
     */
    private fun List<Token>.parseTrim(name: Token): ParseNode {
        if (head?.type != LEFT_PAREN) err("Expected $LEFT_PAREN", PARSE_EXPECTED_LEFT_PAREN_BUILTIN_FUNCTION_CALL)

        var rem = tail
        val arguments = mutableListOf<ParseNode>()

        fun parseArgument(block: (ParseNode) -> ParseNode = { it }): List<Token> {
            val node = block(rem.parseExpression())
            arguments.add(node)

            return node.remaining
        }

        val hasSpecification = when(rem.head?.type) {
            TRIM_SPECIFICATION -> {
                rem = parseArgument()

                true
            }
            else -> false
        }

        if (hasSpecification) { // trim(spec [toRemove] from target)
            rem = when (rem.head?.keywordText) {
                "from" -> rem.tail
                else   -> parseArgument { it.deriveExpectedKeyword("from") }
            }

            rem = parseArgument()
        }
        else {
            if(rem.head?.keywordText == "from") { // trim(from target)
                rem = rem.tail // skips from

                rem = parseArgument()
            }
            else { // trim([toRemove from] target)
                rem = parseArgument()

                if(rem.head?.keywordText == "from") {
                    rem = rem.tail // skips from

                    rem = parseArgument()
                }
            }
        }

        if(rem.head?.type != RIGHT_PAREN) {
            rem.err("Expected $RIGHT_PAREN", PARSE_EXPECTED_RIGHT_PAREN_BUILTIN_FUNCTION_CALL)
        }

        return ParseNode(ParseType.CALL, name, arguments, rem.tail)
    }

    /**
     * Parses extract
     *
     * Syntax is EXTRACT(<date_part> FROM <timestamp>).
     */
    private fun List<Token>.parseExtract(name: Token): ParseNode {
        if (head?.type != LEFT_PAREN) err("Expected $LEFT_PAREN",
                                          PARSE_EXPECTED_LEFT_PAREN_BUILTIN_FUNCTION_CALL)

        var rem = tail

        return when (rem.head?.type) {
            DATE_PART -> {
                val datePart = rem.parseExpression().deriveExpectedKeyword("from")
                rem = datePart.remaining

                val timestamp = rem.parseExpression().deriveExpected(RIGHT_PAREN)

                ParseNode(ParseType.CALL, name, listOf(datePart, timestamp), timestamp.remaining)
            }
            else      -> rem.head.err("Expected one of: $DATE_PART_KEYWORDS", PARSE_EXPECTED_DATE_PART)
        }
    }

    private fun List<Token>.parseListLiteral(): ParseNode =
        parseArgList(
            aliasSupportType = NONE,
            mode = NORMAL_ARG_LIST
        ).copy(
            type = LIST
        ).deriveExpected(RIGHT_BRACKET)

    private fun List<Token>.parseBagLiteral(): ParseNode =
        parseArgList(
            aliasSupportType = NONE,
            mode = NORMAL_ARG_LIST
        ).copy(
            type = BAG
        ).deriveExpected(RIGHT_DOUBLE_ANGLE_BRACKET)

    private fun List<Token>.parseStructLiteral(): ParseNode =
        parseArgList(
            aliasSupportType = NONE,
            mode = STRUCT_LITERAL_ARG_LIST
        ).copy(
            type = STRUCT
        ).deriveExpected(RIGHT_CURLY)

    private fun List<Token>. parseTableValues(): ParseNode =
        parseCommaList {
            var rem = this
            if (rem.head?.type != LEFT_PAREN) {
                err("Expected $LEFT_PAREN for row value constructor", PARSE_EXPECTED_LEFT_PAREN_VALUE_CONSTRUCTOR)
            }
            rem = rem.tail
            rem.parseArgList(
                aliasSupportType = NONE,
                mode = NORMAL_ARG_LIST
            ).copy(
                type = LIST
            ).deriveExpected(RIGHT_PAREN)
        }

    private val parseCommaDelim: List<Token>.() -> ParseNode? = {
        when (head?.type) {
            COMMA -> atomFromHead()
            else -> null
        }
    }

    private val parseJoinDelim: List<Token>.() -> ParseNode? = {
        when (head?.type) {
            COMMA -> atomFromHead(INNER_JOIN)
            KEYWORD -> when (head?.keywordText) {
                "join", "inner_join" -> atomFromHead(INNER_JOIN)
                "left_join" -> atomFromHead(LEFT_JOIN)
                "right_join" -> atomFromHead(RIGHT_JOIN)
                "outer_join" -> atomFromHead(OUTER_JOIN)
                else -> null
            }
            else -> null
        }
    }

    private fun List<Token>.parseArgList(aliasSupportType: AliasSupportType,
                                         mode: ArgListMode): ParseNode {
        val parseDelim = when (mode) {
            FROM_CLAUSE_ARG_LIST -> parseJoinDelim
            else -> parseCommaDelim
        }

        return parseDelimitedList(parseDelim) { delim ->
            var rem = this
            var child = when (mode) {
                STRUCT_LITERAL_ARG_LIST -> {
                    val field = rem.parseExpression().deriveExpected(COLON)
                    rem = field.remaining
                    val value = rem.parseExpression()
                    ParseNode(MEMBER, null, listOf(field, value), value.remaining)
                }
                FROM_CLAUSE_ARG_LIST -> {
                    when (rem.head?.keywordText) {
                        "unpivot" -> {
                            val actualChild = rem.tail.parseExpression()
                            ParseNode(
                                UNPIVOT,
                                null,
                                listOf(actualChild),
                                actualChild.remaining
                            )
                        }
                        else -> rem.parseExpression()
                    }
                }
                else -> rem.parseExpression()
            }
            rem = child.remaining

            val aliasTokenType = rem.head?.type
            if (aliasSupportType.supportsAs
                && (aliasTokenType == AS || aliasTokenType?.isIdentifier() == true)) {
                if (aliasTokenType == AS) {
                    rem = rem.tail
                }
                val name = rem.head
                if (name == null || !name.type.isIdentifier()) {
                    rem.err("Expected identifier for alias", PARSE_EXPECTED_IDENT_FOR_ALIAS)
                }
                rem = rem.tail
                child = ParseNode(AS_ALIAS, name, listOf(child), rem)
            }

            if (aliasSupportType.supportsAt && rem.head?.type == AT) {
                rem = rem.tail
                val name = rem.head
                if (name?.type?.isIdentifier() != true) {
                    rem.err("Expected identifier for AT-name", PARSE_EXPECTED_IDENT_FOR_AT)
                }
                rem = rem.tail
                child = ParseNode(AT_ALIAS, name, listOf(child), rem)
            }

            if (delim?.type?.isJoin == true) {
                val operands = mutableListOf(child)

                // TODO determine if this should be restricted for some joins
                // check for an ON-clause
                if (rem.head?.keywordText == "on") {
                    val onClause = rem.tail.parseExpression()
                    rem = onClause.remaining
                    operands.add(onClause)
                }

                // wrap the join node based on the infix delimiter
                child = delim.copy(
                    children = operands,
                    remaining = rem
                )
            }
            child
        }
    }

    private inline fun List<Token>.parseCommaList(parseItem: List<Token>.() -> ParseNode) =
        parseDelimitedList(parseCommaDelim) { parseItem() }

    /**
     * Parses the given list-like construct.  This is typically for things like argument lists,
     * but can be used for other list-like constructs such as `JOIN` clauses.
     *
     * @param parseDelim the function to parse each delimiter, should return a non-null [ParseNode]
     *  if the delimiter is encountered and `null` if there is no delimiter (i.e. the end of the
     *  list has been reached.
     * @param parseItem the function to parse each item in a list, it is given the [ParseNode]
     *  of the delimiter that was encountered prior to the item to be parsed which could be `null`
     *  for the first item in the list.
     */
    private inline fun List<Token>.parseDelimitedList(parseDelim: List<Token>.() -> ParseNode?,
                                                      parseItem: List<Token>.(delim: ParseNode?) -> ParseNode): ParseNode {
        val items = ArrayList<ParseNode>()
        var delim: ParseNode? = null
        var rem = this

        while (rem.isNotEmpty()) {
            val child = rem.parseItem(delim)
            items.add(child)
            rem = child.remaining

            delim = rem.parseDelim()
            if (delim == null) {
                break
            }
            rem = delim.remaining

        }
        return ParseNode(ARG_LIST, null, items, rem)
    }

    /** Entry point into the parser. */
    override fun parseExprNode(source: String): ExprNode {
        val node = SqlLexer(ion).tokenize(source).parseExpression()
        val rem = node.remaining
        if (!rem.onlyEndOfStatement()) {
            when(rem.head?.type ) {
                SEMICOLON -> rem.tail.err("Unexpected token after semicolon. (Only one query is allowed.)",
                                          PARSE_UNEXPECTED_TOKEN)
                else      -> rem.err("Unexpected token after expression", PARSE_UNEXPECTED_TOKEN)
            }
        }
        return node.toExprNode()
    }

    override fun parse(source: String): IonSexp =
        V0AstSerializer.serialize(parseExprNode(source), ion)
}
