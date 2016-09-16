/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.groovy.parser.antlr4;

import groovy.lang.IntRange;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.groovy.parser.antlr4.util.StringUtil;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.*;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.runtime.IOGroovyMethods;
import org.codehaus.groovy.runtime.StringGroovyMethods;
import org.codehaus.groovy.syntax.Numbers;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.syntax.Types;
import org.objectweb.asm.Opcodes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.groovy.parser.antlr4.GroovyParser.*;
import static org.codehaus.groovy.runtime.DefaultGroovyMethods.*;

/**
 * Created by Daniel.Sun on 2016/8/14.
 */
public class ASTBuilder extends GroovyParserBaseVisitor<Object> implements GroovyParserVisitor<Object> {

    public ASTBuilder(SourceUnit sourceUnit, ClassLoader classLoader) {
        this.sourceUnit = sourceUnit;
        this.moduleNode = new ModuleNode(sourceUnit);

        this.lexer = new GroovyLangLexer(
                new ANTLRInputStream(
                        this.readSourceCode(sourceUnit)));
        this.parser = new GroovyLangParser(
                new CommonTokenStream(this.lexer));

        this.parser.setErrorHandler(new BailErrorStrategy());

        this.setupErrorListener(this.parser);
    }

    public ModuleNode buildAST() {
        try {
            return (ModuleNode) this.visit(parser.compilationUnit());
        } catch (CompilationFailedException e) {
            LOGGER.log(Level.SEVERE, "Failed to build AST", e);

            throw e;
        }
    }

    @Override
    public ModuleNode visitCompilationUnit(CompilationUnitContext ctx) {
        this.visit(ctx.packageDeclaration());

        ctx.statement().stream()
                .map(this::visit)
//                .filter(e -> e instanceof Statement)
                .forEach(e -> {
                    if (e instanceof DeclarationListStatement) { // local variable declaration
                        ((DeclarationListStatement) e).getDeclarationStatements().forEach(moduleNode::addStatement);
                    } else if (e instanceof Statement) {
                        moduleNode.addStatement((Statement) e);
                    } else if (e instanceof MethodNode) { // script method
                        moduleNode.addMethod((MethodNode) e);
                    }
                });

        // if groovy source file only contains blank(including EOF), add "return null" to the AST
        if (this.isBlankScript(ctx)) {
            this.addEmptyReturnStatement();
            return moduleNode;
        }

        return moduleNode;
    }

    @Override
    public PackageNode visitPackageDeclaration(PackageDeclarationContext ctx) {
        String packageName = this.visitQualifiedName(ctx.qualifiedName());
        moduleNode.setPackageName(packageName + DOT_STR);

        PackageNode packageNode = moduleNode.getPackage();

        this.visitAnnotationsOpt(ctx.annotationsOpt()).stream()
                .forEach(packageNode::addAnnotation);

        return this.configureAST(packageNode, ctx);
    }

    @Override
    public ImportNode visitImportDeclaration(ImportDeclarationContext ctx) {
        // GROOVY-6094
        moduleNode.putNodeMetaData(IMPORT_NODE_CLASS, IMPORT_NODE_CLASS);

        ImportNode importNode = null;

        boolean hasStatic = asBoolean(ctx.STATIC());
        boolean hasStar = asBoolean(ctx.MUL());
        boolean hasAlias = asBoolean(ctx.identifier());

        List<AnnotationNode> annotationNodeList = this.visitAnnotationsOpt(ctx.annotationsOpt());

        if (hasStatic) {
            if (hasStar) { // e.g. import static java.lang.Math.*
                String qualifiedName = this.visitQualifiedName(ctx.qualifiedName());
                ClassNode type = ClassHelper.make(qualifiedName);


                moduleNode.addStaticStarImport(type.getText(), type, annotationNodeList);

                importNode = last(moduleNode.getStaticStarImports().values());
            } else { // e.g. import static java.lang.Math.pow
                List<GroovyParserRuleContext> identifierList = new LinkedList<>(ctx.qualifiedName().identifier());
                String name = pop(identifierList).getText();
                ClassNode classNode =
                        ClassHelper.make(
                                identifierList.stream()
                                        .map(ParseTree::getText)
                                        .collect(Collectors.joining(DOT_STR)));
                String alias = hasAlias
                        ? ctx.identifier().getText()
                        : name;

                moduleNode.addStaticImport(classNode, name, alias, annotationNodeList);

                importNode = last(moduleNode.getStaticImports().values());
            }
        } else {
            if (hasStar) { // e.g. import java.util.*
                String qualifiedName = this.visitQualifiedName(ctx.qualifiedName());

                moduleNode.addStarImport(qualifiedName + DOT_STR, annotationNodeList);

                importNode = last(moduleNode.getStarImports());
            } else { // e.g. import java.util.Map
                String qualifiedName = this.visitQualifiedName(ctx.qualifiedName());
                String name = last(ctx.qualifiedName().identifier()).getText();
                ClassNode classNode = ClassHelper.make(qualifiedName);
                String alias = hasAlias
                        ? ctx.identifier().getText()
                        : name;

                moduleNode.addImport(alias, classNode, annotationNodeList);

                importNode = last(moduleNode.getImports());
            }
        }

        // TODO verify whether the following code is useful or not
        // we're using node metadata here in order to fix GROOVY-6094
        // without breaking external APIs
        Object node = moduleNode.getNodeMetaData(IMPORT_NODE_CLASS);
        if (null != node && IMPORT_NODE_CLASS != node) {
            this.configureAST((ImportNode) node, importNode);
        }
        moduleNode.removeNodeMetaData(IMPORT_NODE_CLASS);

        return this.configureAST(importNode, ctx);
    }

    // statement {    --------------------------------------------------------------------
    @Override
    public AssertStatement visitAssertStmtAlt(AssertStmtAltContext ctx) {
        Expression conditionExpression = (Expression) this.visit(ctx.ce);
        BooleanExpression booleanExpression =
                this.configureAST(
                        new BooleanExpression(conditionExpression), conditionExpression);

        if (!asBoolean(ctx.me)) {
            return this.configureAST(
                    new AssertStatement(booleanExpression), ctx);
        }

        return this.configureAST(new AssertStatement(booleanExpression,
                        (Expression) this.visit(ctx.me)),
                ctx);

    }

    @Override
    public IfStatement visitIfElseStmtAlt(IfElseStmtAltContext ctx) {
        Expression conditionExpression = this.visitParExpression(ctx.parExpression());
        BooleanExpression booleanExpression =
                this.configureAST(
                        new BooleanExpression(conditionExpression), conditionExpression);

        return this.configureAST(
                new IfStatement(booleanExpression,
                        (Statement) this.visit(ctx.tb),
                        asBoolean(ctx.ELSE()) ? (Statement) this.visit(ctx.fb) : EmptyStatement.INSTANCE),
                ctx);
    }

    @Override
    public ForStatement visitForStmtAlt(ForStmtAltContext ctx) {
        Pair<Parameter, Expression> controlPair = this.visitForControl(ctx.forControl());

        return this.configureAST(
                new ForStatement(controlPair.getKey(), controlPair.getValue(), (Statement) this.visit(ctx.statement())),
                ctx);
    }

    @Override
    public Pair<Parameter, Expression> visitForControl(ForControlContext ctx) {
        if (asBoolean(ctx.enhancedForControl())) { // e.g. for(int i in 0..<10) {}
            return this.visitEnhancedForControl(ctx.enhancedForControl());
        }

        if (asBoolean(ctx.SEMI())) { // e.g. for(int i = 0; i < 10; i++) {}
            ClosureListExpression closureListExpression = new ClosureListExpression();

            closureListExpression.addExpression(this.visitForInit(ctx.forInit()));
            closureListExpression.addExpression(asBoolean(ctx.expression()) ? (Expression) this.visit(ctx.expression()) : EmptyExpression.INSTANCE);
            closureListExpression.addExpression(this.visitForUpdate(ctx.forUpdate()));

            return new Pair<>(ForStatement.FOR_LOOP_DUMMY, closureListExpression);
        }

        throw createParsingFailedException("Unsupported for control: " + ctx.getText(), ctx);
    }

    @Override
    public Expression visitForInit(ForInitContext ctx) {
        if (!asBoolean(ctx)) {
            return EmptyExpression.INSTANCE;
        }

        if (asBoolean(ctx.localVariableDeclaration())) {
            DeclarationListStatement declarationListStatement = this.visitLocalVariableDeclaration(ctx.localVariableDeclaration());

            List<?> declarationExpressionList = declarationListStatement.getDeclarationExpressions();

            if (declarationExpressionList.size() == 1) {
                return this.configureAST((Expression) declarationExpressionList.get(0), ctx);
            } else {
                return this.configureAST(new ClosureListExpression((List<Expression>) declarationExpressionList), ctx);
            }
        }

        if (asBoolean(ctx.expression())) {
            return this.configureAST((Expression) this.visit(ctx.expression()), ctx);
        }

        throw createParsingFailedException("Unsupported for init: " + ctx.getText(), ctx);
    }

    @Override
    public Expression visitForUpdate(ForUpdateContext ctx) {
        if (!asBoolean(ctx)) {
            return EmptyExpression.INSTANCE;
        }

        return this.configureAST((Expression) this.visit(ctx.expression()), ctx);
    }


    @Override
    public Pair<Parameter, Expression> visitEnhancedForControl(EnhancedForControlContext ctx) {
        Parameter parameter = this.configureAST(
                new Parameter(this.visitType(ctx.type()), this.visitVariableDeclaratorId(ctx.variableDeclaratorId()).getName()),
                ctx.variableDeclaratorId());

        // FIXME Groovy will ignore variableModifier of parameter in the for control
        // In order to make the new parser behave same with the old one, we do not process variableModifier*

        return new Pair<>(parameter, (Expression) this.visit(ctx.expression()));
    }


    @Override
    public WhileStatement visitWhileStmtAlt(WhileStmtAltContext ctx) {
        Expression conditionExpression = this.visitParExpression(ctx.parExpression());
        BooleanExpression booleanExpression =
                this.configureAST(
                        new BooleanExpression(conditionExpression), conditionExpression);

        return this.configureAST(
                new WhileStatement(booleanExpression, (Statement) this.visit(ctx.statement())),
                ctx);
    }

    @Override
    public TryCatchStatement visitTryCatchStmtAlt(TryCatchStmtAltContext ctx) {
        TryCatchStatement tryCatchStatement =
                new TryCatchStatement((Statement) this.visit(ctx.block()),
                        this.visitFinallyBlock(ctx.finallyBlock()));


        ctx.catchClause().stream().map(this::visitCatchClause)
                .reduce(new LinkedList<CatchStatement>(), (r, e) -> {
                    r.addAll(e); // merge several LinkedList<CatchStatement> instances into one LinkedList<CatchStatement> instance
                    return r;
                })
                .forEach(tryCatchStatement::addCatch);

        return this.configureAST(tryCatchStatement, ctx);
    }

    /**
     * Multi-catch(1..*) clause will be unpacked to several normal catch clauses, so the return type is List
     *
     * @param ctx the parse tree
     * @return
     */
    @Override
    public List<CatchStatement> visitCatchClause(CatchClauseContext ctx) {
        // FIXME Groovy will ignore variableModifier of parameter in the catch clause
        // In order to make the new parser behave same with the old one, we do not process variableModifier*

        return this.visitCatchType(ctx.catchType()).stream()
                .map(e -> this.configureAST(
                        new CatchStatement(
                                // FIXME The old parser does not set location info for the parameter of the catch clause.
                                // we could make it better
                                //this.configureAST(new Parameter(e, ctx.Identifier().getText()), ctx.Identifier()),

                                new Parameter(e, ctx.identifier().getText()),
                                this.visitBlock(ctx.block())),
                        ctx.block()))
                .collect(Collectors.toList());
    }

    @Override
    public List<ClassNode> visitCatchType(CatchTypeContext ctx) {
        if (!asBoolean(ctx)) {
            return Collections.singletonList(ClassHelper.OBJECT_TYPE);
        }

        return ctx.qualifiedClassName().stream()
                .map(this::visitQualifiedClassName)
                .collect(Collectors.toList());
    }


    @Override
    public Statement visitFinallyBlock(FinallyBlockContext ctx) {
        if (!asBoolean(ctx)) {
            return EmptyStatement.INSTANCE;
        }

        return this.configureAST(
                this.createBlockStatement((Statement) this.visit(ctx.block())),
                ctx);
    }

    @Override
    public SwitchStatement visitSwitchStmtAlt(SwitchStmtAltContext ctx) {
        List<Statement> statementList =
                ctx.switchBlockStatementGroup().stream()
                        .map(this::visitSwitchBlockStatementGroup)
                        .reduce(new LinkedList<>(), (r, e) -> {
                            r.addAll(e);
                            return r;
                        });

        List<CaseStatement> caseStatementList = new LinkedList<>();
        List<Statement> defaultStatementList = new LinkedList<>();

        statementList.stream().forEach(e -> {
            if (e instanceof CaseStatement) {
                caseStatementList.add((CaseStatement) e);
            } else if (isTrue(e, IS_SWITCH_DEFAULT)) {
                defaultStatementList.add(e);
            }
        });

        int defaultStatementListSize = defaultStatementList.size();
        if (defaultStatementListSize > 1) {
            throw createParsingFailedException("switch statement should have only one default case, which should appear at last", defaultStatementList.get(0));
        }

        if (defaultStatementListSize > 0 && last(statementList) instanceof CaseStatement) {
            throw createParsingFailedException("default case should appear at last", defaultStatementList.get(0));
        }

        return this.configureAST(
                new SwitchStatement(
                        this.visitParExpression(ctx.parExpression()),
                        caseStatementList,
                        defaultStatementListSize == 0 ? EmptyStatement.INSTANCE : defaultStatementList.get(0)
                ),
                ctx);
    }

    @Override
    @SuppressWarnings({"unchecked"})
    public List<Statement> visitSwitchBlockStatementGroup(SwitchBlockStatementGroupContext ctx) {
        int labelCnt = ctx.switchLabel().size();
        List<Token> firstLabelHolder = new ArrayList<>(1);

        return (List<Statement>) ctx.switchLabel().stream()
                .map(e -> (Object) this.visitSwitchLabel(e))
                .reduce(new ArrayList<Statement>(4), (r, e) -> {
                    List<Statement> statementList = (List<Statement>) r;
                    Pair<Token, Expression> pair = (Pair<Token, Expression>) e;

                    boolean isLast = labelCnt - 1 == statementList.size();

                    switch (pair.getKey().getType()) {
                        case CASE: {
                            if (!asBoolean(statementList)) {
                                firstLabelHolder.add(pair.getKey());
                            }

                            statementList.add(
                                    this.configureAST(
                                            new CaseStatement(
                                                    pair.getValue(),

                                                    // check whether processing the last label. if yes, block statement should be attached.
                                                    isLast ? this.visitBlockStatements(ctx.blockStatements())
                                                            : EmptyStatement.INSTANCE
                                            ),
                                            firstLabelHolder.get(0)));

                            break;
                        }
                        case DEFAULT: {

                            BlockStatement blockStatement = this.visitBlockStatements(ctx.blockStatements());
                            blockStatement.putNodeMetaData(IS_SWITCH_DEFAULT, true);

                            statementList.add(
                                    // this.configureAST(blockStatement, pair.getKey())
                                    blockStatement
                            );

                            break;
                        }
                    }

                    return statementList;
                });

    }

    @Override
    public Pair<Token, Expression> visitSwitchLabel(SwitchLabelContext ctx) {
        if (asBoolean(ctx.CASE())) {
            return new Pair<>(ctx.CASE().getSymbol(), (Expression) this.visit(ctx.expression()));
        } else if (asBoolean(ctx.DEFAULT())) {
            return new Pair<>(ctx.DEFAULT().getSymbol(), EmptyExpression.INSTANCE);
        }

        throw createParsingFailedException("Unsupported switch label: " + ctx.getText(), ctx);
    }


    @Override
    public SynchronizedStatement visitSynchronizedStmtAlt(SynchronizedStmtAltContext ctx) {
        return this.configureAST(
                new SynchronizedStatement(this.visitParExpression(ctx.parExpression()), this.visitBlock(ctx.block())),
                ctx);
    }


    @Override
    public ExpressionStatement visitExpressionStmtAlt(ExpressionStmtAltContext ctx) {
        return (ExpressionStatement) this.visit(ctx.statementExpression());
    }

    @Override
    public ReturnStatement visitReturnStmtAlt(ReturnStmtAltContext ctx) {
        return this.configureAST(new ReturnStatement(asBoolean(ctx.expression())
                        ? (Expression) this.visit(ctx.expression())
                        : new ConstantExpression(null)),
                ctx);
    }

    @Override
    public ThrowStatement visitThrowStmtAlt(ThrowStmtAltContext ctx) {
        return this.configureAST(
                new ThrowStatement((Expression) this.visit(ctx.expression())),
                ctx);
    }

    @Override
    public Statement visitLabeledStmtAlt(LabeledStmtAltContext ctx) {
        Statement statement = (Statement) this.visit(ctx.statement());

        statement.addStatementLabel(ctx.identifier().getText());

        return statement; // this.configureAST(statement, ctx);
    }

    @Override
    public BreakStatement visitBreakStmtAlt(BreakStmtAltContext ctx) {
        String label = asBoolean(ctx.identifier())
                ? ctx.identifier().getText()
                : null;

        return this.configureAST(new BreakStatement(label), ctx);
    }

    @Override
    public ContinueStatement visitContinueStmtAlt(ContinueStmtAltContext ctx) {
        String label = asBoolean(ctx.identifier())
                ? ctx.identifier().getText()
                : null;

        return this.configureAST(new ContinueStatement(label), ctx);
    }

    @Override
    public Statement visitLocalVariableDeclarationStmtAlt(LocalVariableDeclarationStmtAltContext ctx) {
        return this.configureAST(this.visitLocalVariableDeclaration(ctx.localVariableDeclaration()), ctx);
    }

    @Override
    public MethodNode visitMethodDeclarationStmtAlt(MethodDeclarationStmtAltContext ctx) {
        return this.configureAST(this.visitMethodDeclaration(ctx.methodDeclaration()), ctx);
    }

// } statement    --------------------------------------------------------------------

    @Override
    public MethodNode visitMethodDeclaration(MethodDeclarationContext ctx) {
        List<ModifierNode> modifierNodeList = Collections.EMPTY_LIST;

        if (asBoolean(ctx.modifiers())) {
            modifierNodeList = this.visitModifiers(ctx.modifiers());
        }

        if (asBoolean(ctx.modifiersOpt())) {
            modifierNodeList = this.visitModifiersOpt(ctx.modifiersOpt());
        }

        MethodNode methodNode =
                new MethodNode(
                        this.visitMethodName(ctx.methodName()),
                        Opcodes.ACC_PUBLIC,
                        this.visitReturnType(ctx.returnType()),
                        this.visitFormalParameters(ctx.formalParameters()),
                        this.visitQualifiedClassNameList(ctx.qualifiedClassNameList()),
                        this.visitMethodBody(ctx.methodBody()));

        ModifierManager modifierManager = new ModifierManager(modifierNodeList);

        boolean isAnnotationDeclaration = false; // TODO
        methodNode.setSyntheticPublic(this.isSyntheticPublic(isAnnotationDeclaration, asBoolean(ctx.returnType()), modifierManager));

        return this.configureAST(
                modifierManager.processMethodNode(methodNode),
                ctx);
    }

    @Override
    public String visitMethodName(MethodNameContext ctx) {
        if (asBoolean(ctx.identifier())) {
            return ctx.identifier().getText();
        }

        if (asBoolean(ctx.StringLiteral())) {
            return this.cleanStringLiteral(ctx.StringLiteral().getText()).getText();
        }

        throw createParsingFailedException("Unsupported method name: " + ctx.getText(), ctx);
    }

    @Override
    public ClassNode visitReturnType(ReturnTypeContext ctx) {
        if (!asBoolean(ctx)) {
            return ClassHelper.OBJECT_TYPE;
        }

        if (asBoolean(ctx.type())) {
            return this.visitType(ctx.type());
        }

        if (asBoolean(ctx.VOID())) {
            return ClassHelper.VOID_TYPE;
        }

        throw createParsingFailedException("Unsupported return type: " + ctx.getText(), ctx);
    }

    @Override
    public Statement visitMethodBody(MethodBodyContext ctx) {
        if (!asBoolean(ctx)) {
            return null;
        }

        return this.configureAST(this.visitBlock(ctx.block()), ctx);
    }


    @Override
    public DeclarationListStatement visitLocalVariableDeclaration(LocalVariableDeclarationContext ctx) {
        List<ModifierNode> modifierNodeList = Collections.EMPTY_LIST;

        if (asBoolean(ctx.variableModifiers())) {
            modifierNodeList = this.visitVariableModifiers(ctx.variableModifiers());
        }

        if (asBoolean(ctx.variableModifiersOpt())) {
            modifierNodeList = this.visitVariableModifiersOpt(ctx.variableModifiersOpt());
        }

        ModifierManager modifierManager =
                new ModifierManager(modifierNodeList);

        if (asBoolean(ctx.typeNamePairs())) { // e.g. def (int a, int b) = [1, 2]
            if (!modifierManager.contains(DEF)) {
                throw createParsingFailedException("keyword def is required to declare tuple, e.g. def (int a, int b) = [1, 2]", ctx);
            }

            return this.configureAST(
                    new DeclarationListStatement(
                            this.configureAST(
                                    modifierManager.processDeclarationExpression(
                                            new DeclarationExpression(
                                                    new ArgumentListExpression(
                                                            this.visitTypeNamePairs(ctx.typeNamePairs()).stream()
                                                                    .peek(e -> modifierManager.processVariableExpression((VariableExpression) e))
                                                                    .collect(Collectors.toList())
                                                    ),
                                                    this.createGroovyTokenByType(ctx.ASSIGN().getSymbol(), Types.ASSIGN),
                                                    this.visitVariableInitializer(ctx.variableInitializer())
                                            )
                                    ),
                                    ctx
                            )
                    ),
                    ctx
            );
        }

        ClassNode classNode = this.visitType(ctx.type());
        List<DeclarationExpression> declarationExpressionList = this.visitVariableDeclarators(ctx.variableDeclarators());

        declarationExpressionList.stream().forEach(e -> {

            VariableExpression veDTO = (VariableExpression) e.getLeftExpression();

            VariableExpression variableExpression =
                    this.configureAST(
                            new VariableExpression(
                                    veDTO.getName(),
                                    classNode),
                            veDTO);


            modifierManager.processVariableExpression(variableExpression);

            e.setLeftExpression(variableExpression);

            modifierManager.processDeclarationExpression(e);
        });

        int size = declarationExpressionList.size();
        if (size > 0) {
            DeclarationExpression declarationExpression = declarationExpressionList.get(0);

            if (1 == size) {
                this.configureAST(declarationExpression, ctx);
            } else {
                // Tweak start of first declaration
                declarationExpression.setLineNumber(ctx.getStart().getLine());
                declarationExpression.setColumnNumber(ctx.getStart().getCharPositionInLine() + 1);
            }
        }

        return this.configureAST(new DeclarationListStatement(declarationExpressionList), ctx);
    }

    @Override
    public List<Expression> visitTypeNamePairs(TypeNamePairsContext ctx) {
        return ctx.typeNamePair().stream().map(this::visitTypeNamePair).collect(Collectors.toList());
    }

    @Override
    public VariableExpression visitTypeNamePair(TypeNamePairContext ctx) {
        return this.configureAST(
                new VariableExpression(
                        this.visitVariableDeclaratorId(ctx.variableDeclaratorId()).getName(),
                        this.visitType(ctx.type())),
                ctx);
    }

    @Override
    public List<DeclarationExpression> visitVariableDeclarators(VariableDeclaratorsContext ctx) {
        return ctx.variableDeclarator().stream().map(this::visitVariableDeclarator).collect(Collectors.toList());
    }

    @Override
    public DeclarationExpression visitVariableDeclarator(VariableDeclaratorContext ctx) {
        org.codehaus.groovy.syntax.Token token;
        if (asBoolean(ctx.ASSIGN())) {
            token = createGroovyTokenByType(ctx.ASSIGN().getSymbol(), Types.ASSIGN);
        } else {
            token = new org.codehaus.groovy.syntax.Token(Types.ASSIGN, "=", ctx.start.getLine(), 1);
        }

        return this.configureAST(
                new DeclarationExpression(
                        this.configureAST(
                                new VariableExpression( // Act as a DTO
                                        this.visitVariableDeclaratorId(ctx.variableDeclaratorId()).getName(),
                                        ClassHelper.OBJECT_TYPE
                                ),
                                ctx.variableDeclaratorId()),
                        token,
                        this.visitVariableInitializer(ctx.variableInitializer())),
                ctx);
    }

    @Override
    public Expression visitVariableInitializer(VariableInitializerContext ctx) {
        if (!asBoolean(ctx)) {
            return EmptyExpression.INSTANCE;
        }

        if (asBoolean(ctx.arrayInitializer())) {
            return this.configureAST(this.visitArrayInitializer(ctx.arrayInitializer()), ctx);
        }

        if (asBoolean(ctx.statementExpression())) {
            return this.configureAST(
                    ((ExpressionStatement) this.visit(ctx.statementExpression())).getExpression(),
                    ctx);
        }

        throw createParsingFailedException("Unsupported variable initializer: " + ctx.getText(), ctx);
    }

    @Override
    public ListExpression visitArrayInitializer(ArrayInitializerContext ctx) {
        return this.configureAST(
                new ListExpression(
                        ctx.variableInitializer().stream()
                                .map(this::visitVariableInitializer)
                                .collect(Collectors.toList())),
                ctx);
    }


    @Override
    public Statement visitBlock(BlockContext ctx) {
        if (!asBoolean(ctx)) {
            return this.createBlockStatement();
        }

        return this.configureAST(
                this.createBlockStatement(
                        ctx.blockStatement().stream()
                                .map(e -> (Statement) this.visit(e))
                                .collect(Collectors.toList())),
                ctx);
    }


    @Override
    public ExpressionStatement visitNormalExprAlt(NormalExprAltContext ctx) {
        return this.configureAST(new ExpressionStatement((Expression) this.visit(ctx.expression())), ctx);
    }

    @Override
    public ExpressionStatement visitCommandExprAlt(CommandExprAltContext ctx) {
        return this.configureAST(new ExpressionStatement(this.visitCommandExpression(ctx.commandExpression())), ctx);
    }

    @Override
    public Expression visitCommandExpression(CommandExpressionContext ctx) {
        Expression baseExpr = this.visitPathExpression(ctx.pathExpression());
        Expression arguments = this.visitArgumentList(ctx.argumentList());

        MethodCallExpression methodCallExpression;
        if (baseExpr instanceof PropertyExpression) { // e.g. obj.a 1, 2
            methodCallExpression =
                    this.configureAST(
                            this.createMethodCallExpression(
                                    (PropertyExpression) baseExpr, arguments),
                            arguments);

        } else if (baseExpr instanceof MethodCallExpression) { // e.g. m {} a  OR  m(...) a
            if (asBoolean(arguments)) {
                throw new GroovyBugError("When baseExpr is a instance of MethodCallExpression, which should follow NO argumentList");
            }

            methodCallExpression = (MethodCallExpression) baseExpr;
        } else if (baseExpr instanceof BinaryExpression) { // e.g. a[x] b
            methodCallExpression =
                    this.configureAST(
                            new MethodCallExpression(
                                    baseExpr,
                                    CALL_STR,
                                    arguments
                            ),
                            arguments
                    );

            methodCallExpression.setImplicitThis(false);
        } else { // e.g. m 1, 2
            methodCallExpression =
                    this.configureAST(
                            this.createMethodCallExpression(baseExpr, arguments),
                            arguments);
        }

        if (!asBoolean(ctx.commandArgument())) {
            return this.configureAST(methodCallExpression, ctx);
        }

        return this.configureAST(
                (Expression) ctx.commandArgument().stream()
                        .map(e -> (Object) e)
                        .reduce(methodCallExpression,
                                (r, e) -> {
                                    CommandArgumentContext commandArgumentContext = (CommandArgumentContext) e;
                                    commandArgumentContext.putNodeMetaData(CMD_EXPRESSION_BASE_EXPR, r);

                                    return this.visitCommandArgument(commandArgumentContext);
                                }
                        ),
                ctx);
    }

    @Override
    public Expression visitCommandArgument(CommandArgumentContext ctx) {
        // e.g. x y a b     we call "x y" as the base expression
        Expression baseExpr = ctx.getNodeMetaData(CMD_EXPRESSION_BASE_EXPR);

        Expression primaryExpr = (Expression) this.visit(ctx.primary());

        if (asBoolean(ctx.argumentList())) { // e.g. x y a b
            if (baseExpr instanceof PropertyExpression) { // the branch should never reach, because a.b.c will be parsed as a path expression, not a method call
                throw createParsingFailedException("Unsupported command argument: " + ctx.getText(), ctx);
            }

            // the following code will process "a b" of "x y a b"
            MethodCallExpression methodCallExpression =
                    new MethodCallExpression(
                            baseExpr,
                            this.createConstantExpression(primaryExpr),
                            this.visitArgumentList(ctx.argumentList())
                    );
            methodCallExpression.setImplicitThis(false);

            return this.configureAST(methodCallExpression, ctx);
        } else if (asBoolean(ctx.pathElement())) { // e.g. x y a.b
            Expression pathExpression =
                    this.createPathExpression(
                            this.configureAST(
                                    new PropertyExpression(baseExpr, this.createConstantExpression(primaryExpr)),
                                    primaryExpr
                            ),
                            ctx.pathElement()
                    );

            return this.configureAST(pathExpression, ctx);
        }

        // e.g. x y a
        return this.configureAST(
                new PropertyExpression(
                        baseExpr,
                        primaryExpr instanceof VariableExpression
                                ? this.createConstantExpression(primaryExpr)
                                : primaryExpr
                ),
                primaryExpr
        );
    }


    // expression {    --------------------------------------------------------------------

    @Override
    public ClassNode visitCastParExpression(CastParExpressionContext ctx) {
        return this.visitType(ctx.type());
    }

    @Override
    public Expression visitParExpression(ParExpressionContext ctx) {
        Expression expression = this.configureAST((Expression) this.visit(ctx.expression()), ctx);

        expression.putNodeMetaData(IS_INSIDE_PARENTHESES, true);

        return expression;
    }


    @Override
    public Expression visitPathExpression(PathExpressionContext ctx) {
        return this.configureAST(
                this.createPathExpression((Expression) this.visit(ctx.primary()), ctx.pathElement()),
                ctx);
    }

    @Override
    public Expression visitPathElement(PathElementContext ctx) {
        Expression baseExpr = ctx.getNodeMetaData(PATH_EXPRESSION_BASE_EXPR);
        Objects.requireNonNull(baseExpr, "baseExpr is required!");

        if (asBoolean(ctx.namePart())) {
            Expression namePartExpr = this.visitNamePart(ctx.namePart());
            GenericsType[] genericsTypes = this.visitNonWildcardTypeArguments(ctx.nonWildcardTypeArguments());


            if (asBoolean(ctx.DOT())) {
                if (asBoolean(ctx.AT())) { // e.g. obj.@a
                    return this.configureAST(new AttributeExpression(baseExpr, namePartExpr), ctx);
                } else { // e.g. obj.p
                    PropertyExpression propertyExpression = new PropertyExpression(baseExpr, namePartExpr);
                    propertyExpression.putNodeMetaData(PATH_EXPRESSION_BASE_EXPR_GENERICS_TYPES, genericsTypes);

                    return this.configureAST(propertyExpression, ctx);
                }
            } else if (asBoolean(ctx.OPTIONAL_DOT())) {
                if (asBoolean(ctx.AT())) { // e.g. obj?.@a
                    return this.configureAST(new AttributeExpression(baseExpr, namePartExpr, true), ctx);
                } else { // e.g. obj?.p
                    PropertyExpression propertyExpression = new PropertyExpression(baseExpr, namePartExpr, true);
                    propertyExpression.putNodeMetaData(PATH_EXPRESSION_BASE_EXPR_GENERICS_TYPES, genericsTypes);

                    return this.configureAST(propertyExpression, ctx);
                }
            } else if (asBoolean(ctx.MEMBER_POINTER())) { // e.g. obj.&m
                return this.configureAST(new MethodPointerExpression(baseExpr, namePartExpr), ctx);
            } else if (asBoolean(ctx.SPREAD_DOT())) {
                if (asBoolean(ctx.AT())) { // e.g. obj*.@a
                    AttributeExpression attributeExpression = new AttributeExpression(baseExpr, namePartExpr, true);

                    attributeExpression.setSpreadSafe(true);

                    return this.configureAST(attributeExpression, ctx);
                } else { // e.g. obj*.p
                    PropertyExpression propertyExpression = new PropertyExpression(baseExpr, namePartExpr, true);
                    propertyExpression.putNodeMetaData(PATH_EXPRESSION_BASE_EXPR_GENERICS_TYPES, genericsTypes);

                    propertyExpression.setSpreadSafe(true);

                    return this.configureAST(propertyExpression, ctx);
                }
            }
        }

        if (asBoolean(ctx.indexPropertyArgs())) { // e.g. list[1, 3, 5]
            Pair<Token, Expression> pair = this.visitIndexPropertyArgs(ctx.indexPropertyArgs());

            return this.configureAST(
                    new BinaryExpression(baseExpr, createGroovyToken(pair.getKey()), pair.getValue()),
                    ctx);
        }

        if (asBoolean(ctx.arguments())) {
            Expression argumentsExpr = this.visitArguments(ctx.arguments());

            if (baseExpr instanceof AttributeExpression) { // e.g. obj.@a(1, 2)
                AttributeExpression attributeExpression = (AttributeExpression) baseExpr;
                attributeExpression.setSpreadSafe(false); // whether attributeExpression is spread safe or not, we must reset it as false

                MethodCallExpression methodCallExpression =
                        new MethodCallExpression(
                                attributeExpression,
                                CALL_STR,
                                argumentsExpr
                        );

                return this.configureAST(methodCallExpression, ctx);
            }

            if (baseExpr instanceof PropertyExpression) { // e.g. obj.a(1, 2)
                MethodCallExpression methodCallExpression =
                        this.createMethodCallExpression((PropertyExpression) baseExpr, argumentsExpr);

                return this.configureAST(methodCallExpression, ctx);
            }


            if (baseExpr instanceof ClosureExpression) { // e.g. {a, b -> a + b }(1, 2)
                MethodCallExpression methodCallExpression =
                        new MethodCallExpression(
                                baseExpr,
                                CALL_STR,
                                argumentsExpr
                        );

                methodCallExpression.setImplicitThis(false);

                return this.configureAST(methodCallExpression, ctx);
            }

            // e.g. m()()
            if (baseExpr instanceof MethodCallExpression) {
                MethodCallExpression methodCallExpression =
                        new MethodCallExpression(
                                baseExpr,
                                CALL_STR,
                                argumentsExpr
                        );

                methodCallExpression.setImplicitThis(false);

                return this.configureAST(methodCallExpression, ctx);
            }


            if (baseExpr instanceof VariableExpression) { // void and primitive type AST node must be an instance of VariableExpression
                String baseExprText = baseExpr.getText();
                if ("void".equals(baseExprText)) { // e.g. void()
                    MethodCallExpression methodCallExpression =
                            new MethodCallExpression(
                                    this.createConstantExpression(baseExpr),
                                    CALL_STR,
                                    argumentsExpr
                            );

                    methodCallExpression.setImplicitThis(false);

                    return this.configureAST(methodCallExpression, ctx);
                } else if (PRIMITIVE_TYPE_SET.contains(baseExprText)) { // e.g. int(), long(), float(), etc.
                    throw createParsingFailedException("Primitive type literal: " + baseExprText + " cannot be used as a method name", ctx);
                }
            }

            if (baseExpr instanceof VariableExpression
                    || baseExpr instanceof GStringExpression
                    || (baseExpr instanceof ConstantExpression && isTrue(baseExpr, IS_STRING))) { // e.g. m(), "$m"(), "m"()

                MethodCallExpression methodCallExpression =
                        this.createMethodCallExpression(baseExpr, argumentsExpr);

                return this.configureAST(methodCallExpression, ctx);
            }

            // e.g. 1(), 1.1(), ((int) 1 / 2)(1, 2)
            MethodCallExpression methodCallExpression =
                    new MethodCallExpression(baseExpr, CALL_STR, argumentsExpr);
            methodCallExpression.setImplicitThis(false);

            return this.configureAST(methodCallExpression, ctx);
        }

        if (asBoolean(ctx.closure())) {
            ClosureExpression closureExpression = this.visitClosure(ctx.closure());

            if (baseExpr instanceof MethodCallExpression) {
                MethodCallExpression methodCallExpression = (MethodCallExpression) baseExpr;
                Expression argumentsExpression = methodCallExpression.getArguments();

                if (argumentsExpression instanceof ArgumentListExpression) { // normal arguments, e.g. 1, 2
                    ArgumentListExpression argumentListExpression = (ArgumentListExpression) argumentsExpression;
                    argumentListExpression.getExpressions().add(closureExpression);

                    return this.configureAST(methodCallExpression, ctx);
                }

                if (argumentsExpression instanceof TupleExpression) { // named arguments, e.g. x: 1, y: 2
                    TupleExpression tupleExpression = (TupleExpression) argumentsExpression;
                    NamedArgumentListExpression namedArgumentListExpression = (NamedArgumentListExpression) tupleExpression.getExpression(0);

                    if (asBoolean(tupleExpression.getExpressions())) {
                        methodCallExpression.setArguments(
                                this.configureAST(
                                        new ArgumentListExpression(
                                                Stream.of(
                                                        this.configureAST(
                                                                new MapExpression(namedArgumentListExpression.getMapEntryExpressions()),
                                                                namedArgumentListExpression
                                                        ),
                                                        closureExpression
                                                ).collect(Collectors.toList())
                                        ),
                                        tupleExpression
                                )
                        );
                    } else {
                        // the branch should never reach, because named arguments must not be empty
                        methodCallExpression.setArguments(
                                this.configureAST(
                                        new ArgumentListExpression(closureExpression),
                                        tupleExpression));
                    }


                    return this.configureAST(methodCallExpression, ctx);
                }

            }

            // e.g. 1 {}, 1.1 {}
            if (baseExpr instanceof ConstantExpression && isTrue(baseExpr, IS_NUMERIC)) {
                MethodCallExpression methodCallExpression =
                        new MethodCallExpression(
                                baseExpr,
                                CALL_STR,
                                this.configureAST(
                                        new ArgumentListExpression(closureExpression),
                                        closureExpression
                                )
                        );
                methodCallExpression.setImplicitThis(false);

                return this.configureAST(methodCallExpression, ctx);
            }


            if (baseExpr instanceof PropertyExpression) {
                PropertyExpression propertyExpression = (PropertyExpression) baseExpr;

                MethodCallExpression methodCallExpression =
                        this.createMethodCallExpression(
                                propertyExpression,
                                this.configureAST(
                                        new ArgumentListExpression(closureExpression),
                                        closureExpression
                                )
                        );

                return this.configureAST(methodCallExpression, ctx);
            }

            // e.g.  m { return 1; }
            MethodCallExpression methodCallExpression =
                    new MethodCallExpression(
                            VariableExpression.THIS_EXPRESSION,

                            (baseExpr instanceof VariableExpression)
                                    ? this.createConstantExpression((VariableExpression) baseExpr)
                                    : baseExpr,

                            this.configureAST(
                                    new ArgumentListExpression(closureExpression),
                                    closureExpression)
                    );


            return this.configureAST(methodCallExpression, ctx);
        }

        throw createParsingFailedException("Unsupported path element: " + ctx.getText(), ctx);
    }


    @Override
    public GenericsType[] visitNonWildcardTypeArguments(NonWildcardTypeArgumentsContext ctx) {
        if (!asBoolean(ctx)) {
            return null;
        }

        return this.visitTypeList(ctx.typeList());
    }

    @Override
    public GenericsType[] visitTypeList(TypeListContext ctx) {
        return ctx.type().stream()
                .map(this::createGenericsType)
                .toArray(GenericsType[]::new);
    }

    @Override
    public Expression visitArguments(ArgumentsContext ctx) {
        if (!asBoolean(ctx.argumentList())) {
            return ArgumentListExpression.EMPTY_ARGUMENTS;
        }

        return this.configureAST(this.visitArgumentList(ctx.argumentList()), ctx);
    }

    @Override
    public Expression visitArgumentList(ArgumentListContext ctx) {
        if (!asBoolean(ctx)) {
            return null;
        }

        if (asBoolean(ctx.expressionList())) { // e.g. arguments like  1, 2
            return this.configureAST(
                    new ArgumentListExpression(
                            this.visitExpressionList(ctx.expressionList())),
                    ctx);
        }

        if (asBoolean(ctx.mapEntryList())) { // e.g. arguments like  x: 1, y: 2
            return this.configureAST(
                    new TupleExpression(
                            this.configureAST(
                                    new NamedArgumentListExpression(
                                            this.visitMapEntryList(ctx.mapEntryList())),
                                    ctx)),
                    ctx);
        }

        throw createParsingFailedException("Unsupported argument list: " + ctx.getText(), ctx);
    }

    @Override
    public Pair<Token, Expression> visitIndexPropertyArgs(IndexPropertyArgsContext ctx) {
        List<Expression> expressionList = this.visitExpressionList(ctx.expressionList());
        Expression indexExpr;

        if (expressionList.size() == 1) {
            Expression expr = expressionList.get(0);

            if (expr instanceof SpreadExpression) { // e.g. a[*[1, 2]]
                ListExpression listExpression = new ListExpression(expressionList);
                listExpression.setWrapped(false);

                indexExpr = listExpression;
            } else { // e.g. a[1]
                indexExpr = expr;
            }
        } else { // e.g. a[1, 2]
            ListExpression listExpression = new ListExpression(expressionList);
            listExpression.setWrapped(true);

            indexExpr = listExpression;
        }

        return new Pair<>(ctx.LBRACK().getSymbol(), this.configureAST(indexExpr, ctx));
    }


    @Override
    public Expression visitNamePart(NamePartContext ctx) {
        if (asBoolean(ctx.identifier())) {
            return this.configureAST(new ConstantExpression(ctx.identifier().getText()), ctx);
        } else if (asBoolean(ctx.StringLiteral())) {
            return this.configureAST(this.cleanStringLiteral(ctx.StringLiteral().getText()), ctx);
        } else if (asBoolean(ctx.dynamicMemberName())) {
            return this.configureAST(this.visitDynamicMemberName(ctx.dynamicMemberName()), ctx);
        } else if (asBoolean(ctx.keywords())) {
            return this.configureAST(new ConstantExpression(ctx.keywords().getText()), ctx);
        }

        throw createParsingFailedException("Unsupported name part: " + ctx.getText(), ctx);
    }

    @Override
    public Expression visitDynamicMemberName(DynamicMemberNameContext ctx) {
        if (asBoolean(ctx.parExpression())) {
            return this.configureAST(this.visitParExpression(ctx.parExpression()), ctx);
        } else if (asBoolean(ctx.gstring())) {
            return this.configureAST(this.visitGstring(ctx.gstring()), ctx);
        }

        throw createParsingFailedException("Unsupported dynamic member name: " + ctx.getText(), ctx);
    }

    @Override
    public Expression visitPostfixExprAlt(PostfixExprAltContext ctx) {
        Expression pathExpr = this.visitPathExpression(ctx.pathExpression());

        if (asBoolean(ctx.op)) {
            return this.configureAST(
                    new PostfixExpression(pathExpr, createGroovyToken(ctx.op)),
                    ctx);
        }

        return this.configureAST(pathExpr, ctx);
    }

    @Override
    public Expression visitUnaryNotExprAlt(UnaryNotExprAltContext ctx) {
        if (asBoolean(ctx.NOT())) {
            return this.configureAST(
                    new NotExpression((Expression) this.visit(ctx.expression())),
                    ctx);
        }

        if (asBoolean(ctx.BITNOT())) {
            return this.configureAST(
                    new BitwiseNegationExpression((Expression) this.visit(ctx.expression())),
                    ctx);
        }

        throw createParsingFailedException("Unsupported unary expression: " + ctx.getText(), ctx);
    }

    @Override
    public CastExpression visitCastExprAlt(CastExprAltContext ctx) {
        return this.configureAST(
                new CastExpression(
                        this.visitCastParExpression(ctx.castParExpression()),
                        (Expression) this.visit(ctx.expression())
                ),
                ctx
        );
    }

    @Override
    public BinaryExpression visitPowerExprAlt(PowerExprAltContext ctx) {
        return this.configureAST(
                this.createBinaryExpression(ctx.left, ctx.op, ctx.right),
                ctx);
    }

    @Override
    public Expression visitUnaryAddExprAlt(UnaryAddExprAltContext ctx) {
        ExpressionContext expressionCtx = ctx.expression();
        Expression expression = (Expression) this.visit(expressionCtx);

        Boolean insidePar = isTrue(expression, IS_INSIDE_PARENTHESES);

        switch (ctx.op.getType()) {
            case ADD: {
                if (expression instanceof ConstantExpression && !insidePar) {
                    return this.configureAST((ConstantExpression) this.visit(expressionCtx), ctx);
                }

                return this.configureAST(new UnaryPlusExpression(expression), ctx);
            }
            case SUB: {
                if (expression instanceof ConstantExpression && !insidePar) {
                    ConstantExpression constantExpression = (ConstantExpression) expression;

                    Object value = constantExpression.getValue();

                    if (value instanceof Integer) {
                        value = -((Integer) value);
                    } else if (value instanceof Long) {
                        value = -((Long) value);
                    } else if (value instanceof Float) {
                        value = -((Float) value);
                    } else if (value instanceof Double) {
                        value = -((Double) value);
                    } else if (value instanceof BigDecimal) {
                        value = ((BigDecimal) value).negate();
                    } else if (value instanceof BigInteger) {
                        value = ((BigInteger) value).negate();
                    } else {
                        throw createParsingFailedException("Unexprected value: " + value, ctx);
                    }

                    return this.configureAST(new ConstantExpression(value, false), ctx);
                }

                return this.configureAST(new UnaryMinusExpression(expression), ctx);
            }

            case INC:
            case DEC:
                return this.configureAST(new PrefixExpression(this.createGroovyToken(ctx.op), expression), ctx);

            default:
                throw createParsingFailedException("Unsupported unary operation: " + ctx.getText(), ctx);
        }
    }

    @Override
    public BinaryExpression visitMultiplicativeExprAlt(MultiplicativeExprAltContext ctx) {
        return this.configureAST(
                this.createBinaryExpression(ctx.left, ctx.op, ctx.right),
                ctx);
    }

    @Override
    public BinaryExpression visitAdditiveExprAlt(AdditiveExprAltContext ctx) {
        return this.configureAST(
                this.createBinaryExpression(ctx.left, ctx.op, ctx.right),
                ctx);
    }

    @Override
    public Expression visitShiftExprAlt(ShiftExprAltContext ctx) {
        Expression left = (Expression) this.visit(ctx.left);
        Expression right = (Expression) this.visit(ctx.right);

        if (asBoolean(ctx.rangeOp)) {
            return this.configureAST(new RangeExpression(left, right, !ctx.rangeOp.getText().endsWith("<")), ctx);
        }

        org.codehaus.groovy.syntax.Token op = null;

        if (asBoolean(ctx.dlOp)) {
            op = this.createGroovyToken(ctx.dlOp, 2);
        } else if (asBoolean(ctx.dgOp)) {
            op = this.createGroovyToken(ctx.dgOp, 2);
        } else if (asBoolean(ctx.tgOp)) {
            op = this.createGroovyToken(ctx.tgOp, 3);
        } else {
            throw createParsingFailedException("Unsupported shift expression: " + ctx.getText(), ctx);
        }

        return this.configureAST(
                new BinaryExpression(left, op, right),
                ctx);
    }


    @Override
    public Expression visitRelationalExprAlt(RelationalExprAltContext ctx) {
        switch (ctx.op.getType()) {
            case AS:
                return this.configureAST(
                        CastExpression.asExpression(this.visitType(ctx.type()), (Expression) this.visit(ctx.left)),
                        ctx);

            case INSTANCEOF:
                return this.configureAST(
                        new BinaryExpression((Expression) this.visit(ctx.left),
                                this.createGroovyToken(ctx.op),
                                this.configureAST(new ClassExpression(this.visitType(ctx.type())), ctx.type())),
                        ctx);

            case LE:
            case GE:
            case GT:
            case LT:
            case IN:
                return this.configureAST(
                        this.createBinaryExpression(ctx.left, ctx.op, ctx.right),
                        ctx);

            default:
                throw createParsingFailedException("Unsupported relational expression: " + ctx.getText(), ctx);
        }
    }


    @Override
    public BinaryExpression visitEqualityExprAlt(EqualityExprAltContext ctx) {
        return this.configureAST(
                this.createBinaryExpression(ctx.left, ctx.op, ctx.right),
                ctx);
    }

    @Override
    public BinaryExpression visitRegexExprAlt(RegexExprAltContext ctx) {
        return this.configureAST(
                this.createBinaryExpression(ctx.left, ctx.op, ctx.right),
                ctx);
    }

    @Override
    public BinaryExpression visitAndExprAlt(AndExprAltContext ctx) {
        return this.configureAST(
                this.createBinaryExpression(ctx.left, ctx.op, ctx.right),
                ctx);
    }

    @Override
    public BinaryExpression visitExclusiveOrExprAlt(ExclusiveOrExprAltContext ctx) {
        return this.configureAST(
                this.createBinaryExpression(ctx.left, ctx.op, ctx.right),
                ctx);
    }

    @Override
    public BinaryExpression visitInclusiveOrExprAlt(InclusiveOrExprAltContext ctx) {
        return this.configureAST(
                this.createBinaryExpression(ctx.left, ctx.op, ctx.right),
                ctx);
    }

    @Override
    public BinaryExpression visitLogicalAndExprAlt(LogicalAndExprAltContext ctx) {
        return this.configureAST(
                this.createBinaryExpression(ctx.left, ctx.op, ctx.right),
                ctx);
    }

    @Override
    public BinaryExpression visitLogicalOrExprAlt(LogicalOrExprAltContext ctx) {
        return this.configureAST(
                this.createBinaryExpression(ctx.left, ctx.op, ctx.right),
                ctx);
    }

    @Override
    public Expression visitConditionalExprAlt(ConditionalExprAltContext ctx) {
        if (asBoolean(ctx.ELVIS())) { // e.g. a == 6 ?: 0
            return this.configureAST(
                    new ElvisOperatorExpression((Expression) this.visit(ctx.con), (Expression) this.visit(ctx.fb)),
                    ctx);
        }

        return this.configureAST(
                new TernaryExpression(
                        this.configureAST(new BooleanExpression((Expression) this.visit(ctx.con)),
                                ctx.con),
                        (Expression) this.visit(ctx.tb),
                        (Expression) this.visit(ctx.fb)),
                ctx);
    }

    @Override
    public BinaryExpression visitAssignmentExprAlt(AssignmentExprAltContext ctx) {
        Expression leftExpr = (Expression) this.visit(ctx.left);

        // the LHS expression should be a variable which is not inside any parentheses
        if (!(leftExpr instanceof VariableExpression
                && !(THIS_STR.equals(leftExpr.getText()) || SUPER_STR.equals(leftExpr.getText()))
                && !isTrue(leftExpr, IS_INSIDE_PARENTHESES))) {

            throw createParsingFailedException("The LHS of an assignment should be a variable", ctx);
        }

        return this.configureAST(
                new BinaryExpression(
                        leftExpr,
                        this.createGroovyToken(ctx.op),
                        ((ExpressionStatement) this.visit(ctx.right)).getExpression()),
                ctx
        );
    }

// } expression    --------------------------------------------------------------------


    // primary {       --------------------------------------------------------------------
    @Override
    public VariableExpression visitIdentifierPrmrAlt(IdentifierPrmrAltContext ctx) {
        return this.configureAST(new VariableExpression(ctx.identifier().getText()), ctx);
    }

    @Override
    public ConstantExpression visitLiteralPrmrAlt(LiteralPrmrAltContext ctx) {
        return this.configureAST((ConstantExpression) this.visit(ctx.literal()), ctx);
    }

    @Override
    public GStringExpression visitGstringPrmrAlt(GstringPrmrAltContext ctx) {
        return this.configureAST((GStringExpression) this.visit(ctx.gstring()), ctx);
    }

    @Override
    public Expression visitNewPrmrAlt(NewPrmrAltContext ctx) {
        return this.configureAST((Expression) this.visit(ctx.creator()), ctx);
    }

    @Override
    public VariableExpression visitThisPrmrAlt(ThisPrmrAltContext ctx) {
        return this.configureAST(new VariableExpression(ctx.THIS().getText()), ctx);
    }

    @Override
    public VariableExpression visitSuperPrmrAlt(SuperPrmrAltContext ctx) {
        return this.configureAST(new VariableExpression(ctx.SUPER().getText()), ctx);
    }


    @Override
    public Expression visitParenPrmrAlt(ParenPrmrAltContext ctx) {
        return this.configureAST(this.visitParExpression(ctx.parExpression()), ctx);
    }

    @Override
    public ListExpression visitListPrmrAlt(ListPrmrAltContext ctx) {
        return this.configureAST(
                this.visitList(ctx.list()),
                ctx);
    }

    @Override
    public MapExpression visitMapPrmrAlt(MapPrmrAltContext ctx) {
        return this.configureAST(this.visitMap(ctx.map()), ctx);
    }


    @Override
    public VariableExpression visitTypePrmrAlt(TypePrmrAltContext ctx) {
        return this.configureAST(
                this.visitBuiltInType(ctx.builtInType()),
                ctx);
    }


// } primary       --------------------------------------------------------------------

    @Override
    public Expression visitCreator(CreatorContext ctx) {
        ClassNode classNode = this.visitCreatedName(ctx.createdName());

        if (asBoolean(ctx.arguments())) { // create instance of class
            return this.configureAST(
                    new ConstructorCallExpression(classNode, this.visitArguments(ctx.arguments())),
                    ctx);
        }

        if (asBoolean(ctx.LBRACK())) { // create array
            Expression[] empties;
            if (asBoolean(ctx.b)) {
                empties = new Expression[ctx.b.size()];
                Arrays.setAll(empties, i -> new ConstantExpression(null));
            } else {
                empties = new Expression[0];
            }

            return this.configureAST(
                    new ArrayExpression(
                            classNode,
                            null,
                            Stream.concat(
                                    ctx.expression().stream()
                                            .map(e -> (Expression) this.visit(e)),
                                    Arrays.stream(empties)
                            ).collect(Collectors.toList())),
                    ctx);
        }

        throw createParsingFailedException("Unsupported creator: " + ctx.getText(), ctx);
    }

    @Override
    public ClassNode visitCreatedName(CreatedNameContext ctx) {
        if (asBoolean(ctx.qualifiedClassName())) {
            ClassNode classNode = this.visitQualifiedClassName(ctx.qualifiedClassName());

            if (asBoolean(ctx.typeArgumentsOrDiamond())) {
                classNode.setGenericsTypes(
                        this.visitTypeArgumentsOrDiamond(ctx.typeArgumentsOrDiamond()));
            }

            return this.configureAST(classNode, ctx);
        }

        if (asBoolean(ctx.primitiveType())) {
            return this.configureAST(
                    this.visitPrimitiveType(ctx.primitiveType()),
                    ctx);
        }

        throw createParsingFailedException("Unsupported created name: " + ctx.getText(), ctx);
    }


    @Override
    public MapExpression visitMap(MapContext ctx) {
        return this.configureAST(
                new MapExpression(this.visitMapEntryList(ctx.mapEntryList())),
                ctx);
    }

    @Override
    public List<MapEntryExpression> visitMapEntryList(MapEntryListContext ctx) {
        return ctx.mapEntry().stream()
                .map(this::visitMapEntry)
                .collect(Collectors.toList());
    }

    @Override
    public MapEntryExpression visitMapEntry(MapEntryContext ctx) {
        Expression keyExpr;
        Expression valueExpr = (Expression) this.visit(ctx.expression());

        if (asBoolean(ctx.MUL())) {
            keyExpr = this.configureAST(new SpreadMapExpression(valueExpr), ctx);
        } else if (asBoolean(ctx.mapEntryLabel())) {
            keyExpr = this.visitMapEntryLabel(ctx.mapEntryLabel());
        } else {
            throw createParsingFailedException("Unsupported map entry: " + ctx.getText(), ctx);
        }

        return this.configureAST(
                new MapEntryExpression(keyExpr, valueExpr),
                ctx);
    }

    @Override
    public Expression visitMapEntryLabel(MapEntryLabelContext ctx) {
        if (asBoolean(ctx.keywords())) {
            return this.configureAST(this.visitKeywords(ctx.keywords()), ctx);
        } else if (asBoolean(ctx.primary())) {
            Expression expression = (Expression) this.visit(ctx.primary());

            // if the key is variable and not inside parentheses, convert it to a constant, e.g. [a:1, b:2]
            if (expression instanceof VariableExpression && !isTrue(expression, IS_INSIDE_PARENTHESES)) {
                expression =
                        this.configureAST(
                                new ConstantExpression(((VariableExpression) expression).getName()),
                                expression);
            }

            return this.configureAST(expression, ctx);
        }

        throw createParsingFailedException("Unsupported map entry label: " + ctx.getText(), ctx);
    }

    @Override
    public ConstantExpression visitKeywords(KeywordsContext ctx) {
        return this.configureAST(new ConstantExpression(ctx.getText()), ctx);
    }

    /*
    @Override
    public VariableExpression visitIdentifier(IdentifierContext ctx) {
        return this.configureAST(new VariableExpression(ctx.getText()), ctx);
    }
    */

    @Override
    public VariableExpression visitBuiltInType(BuiltInTypeContext ctx) {
        String text;
        if (asBoolean(ctx.VOID())) {
            text = ctx.VOID().getText();
        } else if (asBoolean(ctx.BuiltInPrimitiveType())) {
            text = ctx.BuiltInPrimitiveType().getText();
        } else {
            throw createParsingFailedException("Unsupported built-in type: " + ctx, ctx);
        }

        return this.configureAST(new VariableExpression(text), ctx);
    }


    @Override
    public ListExpression visitList(ListContext ctx) {
        return this.configureAST(
                new ListExpression(
                        this.visitExpressionList(ctx.expressionList())),
                ctx);
    }

    @Override
    public List<Expression> visitExpressionList(ExpressionListContext ctx) {
        if (!asBoolean(ctx)) {
            return Collections.EMPTY_LIST;
        }

        return ctx.expressionListElement().stream()
                .map(e -> this.visitExpressionListElement(e))
                .collect(Collectors.toList());
    }

    @Override
    public Expression visitExpressionListElement(ExpressionListElementContext ctx) {
        Expression expression = (Expression) this.visit(ctx.expression());

        if (asBoolean(ctx.MUL())) {
            return this.configureAST(new SpreadExpression(expression), ctx);
        }

        return this.configureAST(expression, ctx);
    }


    // literal {       --------------------------------------------------------------------
    @Override
    public ConstantExpression visitIntegerLiteralAlt(IntegerLiteralAltContext ctx) {
        String text = ctx.IntegerLiteral().getText();

        ConstantExpression constantExpression = new ConstantExpression(Numbers.parseInteger(null, text), !text.startsWith(SUB_STR));
        constantExpression.putNodeMetaData(IS_NUMERIC, true);

        return this.configureAST(constantExpression, ctx);
    }

    @Override
    public ConstantExpression visitFloatingPointLiteralAlt(FloatingPointLiteralAltContext ctx) {
        String text = ctx.FloatingPointLiteral().getText();

        ConstantExpression constantExpression = new ConstantExpression(Numbers.parseDecimal(text), !text.startsWith(SUB_STR));
        constantExpression.putNodeMetaData(IS_NUMERIC, true);

        return this.configureAST(constantExpression, ctx);
    }


    @Override
    public ConstantExpression visitStringLiteralAlt(StringLiteralAltContext ctx) {
        return this.configureAST(
                this.cleanStringLiteral(ctx.StringLiteral().getText()),
                ctx);
    }


    @Override
    public ConstantExpression visitBooleanLiteralAlt(BooleanLiteralAltContext ctx) {
        return this.configureAST(new ConstantExpression("true".equals(ctx.BooleanLiteral().getText()), true), ctx);
    }

    @Override
    public ConstantExpression visitNullLiteralAlt(NullLiteralAltContext ctx) {
        return this.configureAST(new ConstantExpression(null), ctx);
    }

    /*
    @Override
    public PropertyExpression visitClassLiteralAlt(ClassLiteralAltContext ctx) {
        return this.configureAST(this.visitClassLiteral(ctx.classLiteral()), ctx);
    }
    */

// } literal       --------------------------------------------------------------------


    /*
    @Override
    public PropertyExpression visitClassLiteral(ClassLiteralContext ctx) {
        return null; // class literal will be treated as path expression, so the node will not be visited
    }
    */

    // gstring {       --------------------------------------------------------------------
    @Override
    public GStringExpression visitGstring(GstringContext ctx) {
        List<ConstantExpression> strings = new LinkedList<>();

        String begin = ctx.GStringBegin().getText();
        final int slashyType = begin.startsWith("/")
                ? StringUtil.SLASHY
                : begin.startsWith("$/") ? StringUtil.DOLLAR_SLASHY : StringUtil.NONE_SLASHY;

        {
            String it = begin;
            if (it.startsWith("\"\"\"")) {
                it = StringUtil.removeCR(it);
                it = it.substring(2); // translate leading """ to "
            } else if (it.startsWith("$/")) {
                it = StringUtil.removeCR(it);
                it = "\"" + it.substring(2); // translate leading $/ to "
            }

            it = StringUtil.replaceEscapes(it, slashyType);
            it = (it.length() == 2)
                    ? ""
                    : StringGroovyMethods.getAt(it, new IntRange(true, 1, -2));

            strings.add(this.configureAST(new ConstantExpression(it), ctx.GStringBegin()));
        }

        List<ConstantExpression> partStrings =
                ctx.GStringPart().stream()
                        .map(e -> {
                            String it = e.getText();

                            it = StringUtil.removeCR(it);
                            it = StringUtil.replaceEscapes(it, slashyType);
                            it = it.length() == 1 ? "" : StringGroovyMethods.getAt(it, new IntRange(true, 0, -2));

                            return this.configureAST(new ConstantExpression(it), e);
                        }).collect(Collectors.toList());
        strings.addAll(partStrings);

        {
            String it = ctx.GStringEnd().getText();
            if (it.endsWith("\"\"\"")) {
                it = StringUtil.removeCR(it);
                it = StringGroovyMethods.getAt(it, new IntRange(true, 0, -3)); // translate tailing """ to "
            } else if (it.endsWith("/$")) {
                it = StringUtil.removeCR(it);
                it = StringGroovyMethods.getAt(it, new IntRange(false, 0, -2)) + "\""; // translate tailing /$ to "
            }

            it = StringUtil.replaceEscapes(it, slashyType);
            it = (it.length() == 1)
                    ? ""
                    : StringGroovyMethods.getAt(it, new IntRange(true, 0, -2));

            strings.add(this.configureAST(new ConstantExpression(it), ctx.GStringEnd()));
        }

        List<Expression> values = ctx.gstringValue().stream()
                .map(e -> {
                    Expression expression = this.visitGstringValue(e);

                    if (expression instanceof ClosureExpression && !asBoolean(e.closure().ARROW())) {
                        return this.configureAST(new MethodCallExpression(expression, CALL_STR, new ArgumentListExpression()), e);
                    }

                    return expression;
                })
                .collect(Collectors.toList());

        StringBuilder verbatimText = new StringBuilder(ctx.getText().length());
        for (int i = 0, n = strings.size(), s = values.size(); i < n; i++) {
            verbatimText.append(strings.get(i).getValue());

            if (i == s) {
                continue;
            }

            Expression value = values.get(i);
            if (!asBoolean(value)) {
                continue;
            }

            verbatimText.append(DOLLAR_STR);
            verbatimText.append(value.getText());
        }

        return this.configureAST(new GStringExpression(verbatimText.toString(), strings, values), ctx);
    }

    @Override
    public Expression visitGstringValue(GstringValueContext ctx) {
        if (asBoolean(ctx.gstringPath())) {
            return this.configureAST(this.visitGstringPath(ctx.gstringPath()), ctx);
        }

        if (asBoolean(ctx.LBRACE())) {
            if (asBoolean(ctx.expression())) {
                return this.configureAST((Expression) this.visit(ctx.expression()), ctx);
            } else { // e.g. "${}"
                return this.configureAST(new ConstantExpression(null), ctx);
            }
        }

        if (asBoolean(ctx.closure())) {
            return this.configureAST(this.visitClosure(ctx.closure()), ctx);
        }

        throw createParsingFailedException("Unsupported gstring value: " + ctx.getText(), ctx);
    }

    @Override
    public Expression visitGstringPath(GstringPathContext ctx) {
        VariableExpression variableExpression = new VariableExpression(ctx.identifier().getText());

        if (asBoolean(ctx.GStringPathPart())) {
            Expression propertyExpression = ctx.GStringPathPart().stream()
                    .map(e -> this.configureAST((Expression) new ConstantExpression(e.getText().substring(1)), e))
                    .reduce(this.configureAST(variableExpression, ctx.identifier()), (r, e) -> this.configureAST(new PropertyExpression(r, e), e));

            return this.configureAST(propertyExpression, ctx);
        }

        return this.configureAST(variableExpression, ctx);
    }
// } gstring       --------------------------------------------------------------------


    @Override
    public ClosureExpression visitClosure(ClosureContext ctx) {
        Parameter[] parameters = asBoolean(ctx.formalParameterList())
                ? this.visitFormalParameterList(ctx.formalParameterList())
                : null;

        if (!asBoolean(ctx.ARROW())) {
            parameters = Parameter.EMPTY_ARRAY;
        }

        Statement code = this.visitBlockStatementsOpt(ctx.blockStatementsOpt());

        return this.configureAST(new ClosureExpression(parameters, code), ctx);
    }

    @Override
    public Parameter[] visitFormalParameters(FormalParametersContext ctx) {
        return this.visitFormalParameterList(ctx.formalParameterList());
    }

    @Override
    public Parameter[] visitFormalParameterList(FormalParameterListContext ctx) {
        List<Parameter> parameterList = new LinkedList<>();

        if (asBoolean(ctx.formalParameter())) {
            parameterList.addAll(
                    ctx.formalParameter().stream()
                            .map(this::visitFormalParameter)
                            .collect(Collectors.toList()));
        }

        if (asBoolean(ctx.lastFormalParameter())) {
            parameterList.add(this.visitLastFormalParameter(ctx.lastFormalParameter()));
        }

        return parameterList.toArray(new Parameter[0]);
    }

    @Override
    public Parameter visitFormalParameter(FormalParameterContext ctx) {
        return this.processFormalParameter(ctx, ctx.variableModifiersOpt(), ctx.type(), null, ctx.variableDeclaratorId(), ctx.expression());
    }

    @Override
    public Parameter visitLastFormalParameter(LastFormalParameterContext ctx) {
        return this.processFormalParameter(ctx, ctx.variableModifiersOpt(), ctx.type(), ctx.ELLIPSIS(), ctx.variableDeclaratorId(), ctx.expression());
    }

    @Override
    public ModifierNode visitClassOrInterfaceModifier(ClassOrInterfaceModifierContext ctx) {
        if (asBoolean(ctx.annotation())) {
            return this.configureAST(new ModifierNode(this.visitAnnotation(ctx.annotation()), ctx.getText()), ctx);
        }

        if (asBoolean(ctx.m)) {
            return this.configureAST(new ModifierNode(ctx.m.getType(), ctx.getText()), ctx);
        }

        throw createParsingFailedException("Unsupported class or interface modifier: " + ctx.getText(), ctx);
    }

    @Override
    public ModifierNode visitModifier(ModifierContext ctx) {
        if (asBoolean(ctx.classOrInterfaceModifier())) {
            return this.configureAST(this.visitClassOrInterfaceModifier(ctx.classOrInterfaceModifier()), ctx);
        }

        if (asBoolean(ctx.m)) {
            return this.configureAST(new ModifierNode(ctx.m.getType(), ctx.getText()), ctx);
        }

        throw createParsingFailedException("Unsupported modifier: " + ctx.getText(), ctx);
    }

    @Override
    public List<ModifierNode> visitModifiers(ModifiersContext ctx) {
        return ctx.modifier().stream()
                .map(this::visitModifier)
                .collect(Collectors.toList());
    }

    @Override
    public List<ModifierNode> visitModifiersOpt(ModifiersOptContext ctx) {
        if (asBoolean(ctx.modifiers())) {
            return this.visitModifiers(ctx.modifiers());
        }

        return Collections.EMPTY_LIST;
    }


    @Override
    public ModifierNode visitVariableModifier(VariableModifierContext ctx) {
        if (asBoolean(ctx.annotation())) {
            return this.configureAST(new ModifierNode(this.visitAnnotation(ctx.annotation()), ctx.getText()), ctx);
        }

        Integer modifierType = null;
        if (asBoolean(ctx.FINAL())) {
            modifierType = ctx.FINAL().getSymbol().getType();
        } else if (asBoolean(ctx.DEF())) {
            modifierType = ctx.DEF().getSymbol().getType();
        }

        if (asBoolean((Object) modifierType)) {
            return this.configureAST(new ModifierNode(modifierType, ctx.getText()), ctx);
        }

        throw createParsingFailedException("Unsupported variable modifier", ctx);
    }

    @Override
    public List<ModifierNode> visitVariableModifiersOpt(VariableModifiersOptContext ctx) {
        if (asBoolean(ctx.variableModifiers())) {
            return this.visitVariableModifiers(ctx.variableModifiers());
        }

        return Collections.EMPTY_LIST;
    }

    @Override
    public List<ModifierNode> visitVariableModifiers(VariableModifiersContext ctx) {
        return ctx.variableModifier().stream()
                .map(this::visitVariableModifier)
                .collect(Collectors.toList());
    }


    // type {       --------------------------------------------------------------------
    @Override
    public ClassNode visitType(TypeContext ctx) {
        if (!asBoolean(ctx)) {
            return ClassHelper.OBJECT_TYPE;
        }

        ClassNode classNode = null;

        if (asBoolean(ctx.classOrInterfaceType())) {
            classNode = this.visitClassOrInterfaceType(ctx.classOrInterfaceType());

            if (asBoolean(ctx.LBRACK())) {
                classNode.setGenericsTypes(null); // clear array's generics type info. Groovy's bug? array's generics type will be ignored. e.g. List<String>[]... p
            }
        }

        if (asBoolean(ctx.primitiveType())) {
            classNode = this.visitPrimitiveType(ctx.primitiveType());
        }

        if (asBoolean(ctx.LBRACK())) {
            for (int i = 0, n = ctx.LBRACK().size(); i < n; i++) {
                classNode = this.configureAST(classNode.makeArray(), classNode);
            }
        }

        if (!asBoolean(classNode)) {
            throw createParsingFailedException("Unsupported type: " + ctx.getText(), ctx);
        }

        return this.configureAST(classNode, ctx);
    }

    @Override
    public ClassNode visitClassOrInterfaceType(ClassOrInterfaceTypeContext ctx) {
        ClassNode classNode = this.visitQualifiedClassName(ctx.qualifiedClassName());

        if (asBoolean(ctx.typeArguments())) {
            classNode.setGenericsTypes(
                    this.visitTypeArguments(ctx.typeArguments()));
        }

        return this.configureAST(classNode, ctx);
    }

    @Override
    public GenericsType[] visitTypeArgumentsOrDiamond(TypeArgumentsOrDiamondContext ctx) {
        if (asBoolean(ctx.typeArguments())) {
            return this.visitTypeArguments(ctx.typeArguments());
        }

        if (asBoolean(ctx.LT())) { // e.g. <>
            return new GenericsType[0];
        }

        throw createParsingFailedException("Unsupported type arguments or diamond: " + ctx.getText(), ctx);
    }


    @Override
    public GenericsType[] visitTypeArguments(TypeArgumentsContext ctx) {
        return ctx.typeArgument().stream().map(this::visitTypeArgument).toArray(GenericsType[]::new);
    }

    @Override
    public GenericsType visitTypeArgument(TypeArgumentContext ctx) {
        if (asBoolean(ctx.QUESTION())) {
            ClassNode baseType = this.configureAST(ClassHelper.makeWithoutCaching(QUESTION_STR), ctx.QUESTION());

            if (!asBoolean(ctx.type())) {
                GenericsType genericsType = new GenericsType(baseType);
                genericsType.setWildcard(true);
                genericsType.setName(QUESTION_STR);

                return this.configureAST(genericsType, ctx);
            }

            ClassNode[] upperBounds = null;
            ClassNode lowerBound = null;

            ClassNode classNode = this.visitType(ctx.type());
            if (asBoolean(ctx.EXTENDS())) {
                upperBounds = new ClassNode[]{classNode};
            } else if (asBoolean(ctx.SUPER())) {
                lowerBound = classNode;
            }

            GenericsType genericsType = new GenericsType(baseType, upperBounds, lowerBound);
            genericsType.setWildcard(true);
            genericsType.setName(QUESTION_STR);

            return this.configureAST(genericsType, ctx);
        } else if (asBoolean(ctx.type())) {
            return this.configureAST(this.createGenericsType(ctx.type()), ctx);
        }

        throw createParsingFailedException("Unsupported type argument: " + ctx.getText(), ctx);
    }

    @Override
    public ClassNode visitPrimitiveType(PrimitiveTypeContext ctx) {
        return this.configureAST(ClassHelper.make(ctx.getText()), ctx);
    }
// } type       --------------------------------------------------------------------

    @Override
    public VariableExpression visitVariableDeclaratorId(VariableDeclaratorIdContext ctx) {
        return this.configureAST(new VariableExpression(ctx.identifier().getText()), ctx);
    }

    @Override
    public BlockStatement visitBlockStatementsOpt(BlockStatementsOptContext ctx) {
        if (asBoolean(ctx.blockStatements())) {
            return this.configureAST(this.visitBlockStatements(ctx.blockStatements()), ctx);
        }

        return this.configureAST(this.createBlockStatement(), ctx);
    }

    @Override
    public BlockStatement visitBlockStatements(BlockStatementsContext ctx) {
        return this.configureAST(
                this.createBlockStatement(
                        ctx.blockStatement().stream()
                                .map(this::visitBlockStatement).collect(Collectors.toList())),
                ctx);
    }


    @Override
    public Statement visitBlockStatement(BlockStatementContext ctx) {
        if (asBoolean(ctx.localVariableDeclaration())) {
            return this.configureAST(this.visitLocalVariableDeclaration(ctx.localVariableDeclaration()), ctx);
        }

        if (asBoolean(ctx.statement())) {
            return (Statement) this.visit(ctx.statement()); //this.configureAST((Statement) this.visit(ctx.statement()), ctx);
        }

        if (asBoolean(ctx.typeDeclaration())) {
            return null; // TODO
        }

        throw createParsingFailedException("Unsupported block statement: " + ctx.getText(), ctx);
    }


    @Override
    public List<AnnotationNode> visitAnnotationsOpt(AnnotationsOptContext ctx) {
        return ctx.annotation().stream()
                .map(this::visitAnnotation)
                .collect(Collectors.toList());
    }

    @Override
    public AnnotationNode visitAnnotation(AnnotationContext ctx) {
        String annotationName = this.visitAnnotationName(ctx.annotationName());

        AnnotationNode annotationNode = new AnnotationNode(ClassHelper.make(annotationName));

        if (asBoolean(ctx.elementValuePairs())) {
            this.visitElementValuePairs(ctx.elementValuePairs()).entrySet().stream().forEach(e -> {
                annotationNode.addMember(e.getKey(), e.getValue());
            });
        } else if (asBoolean(ctx.elementValue())) {
            annotationNode.addMember(VALUE_STR, this.visitElementValue(ctx.elementValue()));
        }

        return this.configureAST(annotationNode, ctx);
    }

    @Override
    public String visitAnnotationName(AnnotationNameContext ctx) {
        return this.visitQualifiedClassName(ctx.qualifiedClassName()).getName();
    }

    @Override
    public Map<String, Expression> visitElementValuePairs(ElementValuePairsContext ctx) {
        return ctx.elementValuePair().stream()
                .map(this::visitElementValuePair)
                .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
    }

    @Override
    public Pair<String, Expression> visitElementValuePair(ElementValuePairContext ctx) {
        return new Pair<>(ctx.identifier().getText(), this.visitElementValue(ctx.elementValue()));
    }

    @Override
    public Expression visitElementValue(ElementValueContext ctx) {
        if (asBoolean(ctx.expression())) {
            return this.configureAST((Expression) this.visit(ctx.expression()), ctx);
        }

        if (asBoolean(ctx.annotation())) {
            return this.configureAST(new AnnotationConstantExpression(this.visitAnnotation(ctx.annotation())), ctx);
        }

        if (asBoolean(ctx.elementValueArrayInitializer())) {
            return this.configureAST(this.visitElementValueArrayInitializer(ctx.elementValueArrayInitializer()), ctx);
        }

        throw createParsingFailedException("Unsupported element value: " + ctx.getText(), ctx);
    }

    @Override
    public ListExpression visitElementValueArrayInitializer(ElementValueArrayInitializerContext ctx) {
        return this.configureAST(new ListExpression(ctx.elementValue().stream().map(this::visitElementValue).collect(Collectors.toList())), ctx);
    }

    @Override
    public String visitQualifiedName(QualifiedNameContext ctx) {
        return ctx.identifier().stream()
                .map(ParseTree::getText)
                .collect(Collectors.joining(DOT_STR));
    }

    @Override
    public ClassNode[] visitQualifiedClassNameList(QualifiedClassNameListContext ctx) {
        if (!asBoolean(ctx)) {
            return new ClassNode[0];
        }

        return ctx.qualifiedClassName().stream()
                .map(this::visitQualifiedClassName)
                .toArray(ClassNode[]::new);
    }

    @Override
    public ClassNode visitQualifiedClassName(QualifiedClassNameContext ctx) {
        String className = ctx.className().getText();

        if (asBoolean(ctx.Identifier())) {
            return ClassHelper.make(ctx.Identifier().stream().map(e -> e.getText()).collect(Collectors.joining("."))
                    + "."
                    + className);
        }

        return ClassHelper.make(className);
    }

    /**
     * Visit tree safely, no NPE occurred when the tree is null.
     *
     * @param tree an AST node
     * @return the visiting result
     */
    @Override
    public Object visit(ParseTree tree) {
        if (!asBoolean(tree)) {
            return null;
        }

        return super.visit(tree);
    }


    // e.g. obj.a(1, 2) or obj.a 1, 2
    private MethodCallExpression createMethodCallExpression(PropertyExpression propertyExpression, Expression arguments) {
        MethodCallExpression methodCallExpression =
                new MethodCallExpression(
                        propertyExpression.getObjectExpression(),
                        propertyExpression.getProperty(),
                        arguments
                );

        methodCallExpression.setImplicitThis(false);
        methodCallExpression.setSafe(propertyExpression.isSafe());
        methodCallExpression.setSpreadSafe(propertyExpression.isSpreadSafe());

        // method call obj*.m(): "safe"(false) and "spreadSafe"(true)
        // property access obj*.p: "safe"(true) and "spreadSafe"(true)
        // so we have to reset safe here.
        if (propertyExpression.isSpreadSafe()) {
            methodCallExpression.setSafe(false);
        }

        // if the generics types meta data is not empty, it is a generic method call, e.g. obj.<Integer>a(1, 2)
        methodCallExpression.setGenericsTypes(
                propertyExpression.getNodeMetaData(PATH_EXPRESSION_BASE_EXPR_GENERICS_TYPES));

        return methodCallExpression;
    }

    // e.g. m(1, 2) or m 1, 2
    private MethodCallExpression createMethodCallExpression(Expression baseExpr, Expression arguments) {
        MethodCallExpression methodCallExpression =
                new MethodCallExpression(
                        VariableExpression.THIS_EXPRESSION,

                        (baseExpr instanceof VariableExpression)
                                ? this.createConstantExpression((VariableExpression) baseExpr)
                                : baseExpr,

                        arguments
                );

        return methodCallExpression;
    }

    private Parameter processFormalParameter(GroovyParserRuleContext ctx,
                                             VariableModifiersOptContext variableModifiersOptContext,
                                             TypeContext typeContext,
                                             TerminalNode ellipsis,
                                             VariableDeclaratorIdContext variableDeclaratorIdContext,
                                             ExpressionContext expressionContext) {

        ClassNode classNode = this.visitType(typeContext);

        if (asBoolean(ellipsis)) {
            classNode = this.configureAST(classNode.makeArray(), classNode);
        }

        Parameter parameter =
                new ModifierManager(this.visitVariableModifiersOpt(variableModifiersOptContext))
                        .processParameter(
                                this.configureAST(
                                        new Parameter(classNode, this.visitVariableDeclaratorId(variableDeclaratorIdContext).getName()),
                                        ctx)
                        );

        if (asBoolean(expressionContext)) {
            parameter.setInitialExpression((Expression) this.visit(expressionContext));
        }

        return parameter;
    }

    private Expression createPathExpression(Expression primaryExpr, List<PathElementContext> pathElementContextList) {
        return (Expression) pathElementContextList.stream()
                .map(e -> (Object) e)
                .reduce(primaryExpr,
                        (r, e) -> {
                            PathElementContext pathElementContext = (PathElementContext) e;

                            pathElementContext.putNodeMetaData(PATH_EXPRESSION_BASE_EXPR, r);

                            return this.visitPathElement(pathElementContext);
                        }
                );
    }

    private GenericsType createGenericsType(TypeContext ctx) {
        return this.configureAST(new GenericsType(this.visitType(ctx)), ctx);
    }

    private ConstantExpression createConstantExpression(Expression expression) {
        return this.configureAST(new ConstantExpression(expression.getText()), expression);
    }

    private BinaryExpression createBinaryExpression(ExpressionContext left, Token op, ExpressionContext right) {
        return new BinaryExpression((Expression) this.visit(left), this.createGroovyToken(op), (Expression) this.visit(right));
    }

    private BlockStatement createBlockStatement(Statement... statements) {
        return this.createBlockStatement(Arrays.asList(statements));
    }

    private BlockStatement createBlockStatement(List<Statement> statementList) {
        return (BlockStatement) statementList.stream()
                .reduce(new BlockStatement(), (r, e) -> {
                    BlockStatement blockStatement = (BlockStatement) r;

                    if (e instanceof DeclarationListStatement) {
                        ((DeclarationListStatement) e).getDeclarationStatements().forEach(blockStatement::addStatement);
                    } else {
                        blockStatement.addStatement(e);
                    }

                    return blockStatement;
                });
    }

    private boolean isSyntheticPublic(
            boolean isAnnotationDeclaration,
            boolean hasReturnType,
            ModifierManager modifierManager
    ) {
        return this.isSyntheticPublic(
                isAnnotationDeclaration,
                modifierManager.containsAnnotations(),
                modifierManager.containsVisibilityModifier(),
                modifierManager.containsNonVisibilityModifier(),
                hasReturnType,
                modifierManager.contains(DEF));
    }

    /**
     * @param isAnnotationDeclaration whether the method is defined in an annotation
     * @param hasAnnotation           whether the method declaration has annotations
     * @param hasVisibilityModifier   whether the method declaration contains visibility modifier(e.g. public, protected, private)
     * @param hasModifier             whether the method declaration has modifier(e.g. visibility modifier, final, static and so on)
     * @param hasReturnType           whether the method declaration has an return type(e.g. String, generic types)
     * @param hasDef                  whether the method declaration using def keyword
     * @return the result
     */
    private boolean isSyntheticPublic(
            boolean isAnnotationDeclaration,
            boolean hasAnnotation,
            boolean hasVisibilityModifier,
            boolean hasModifier,
            boolean hasReturnType,
            boolean hasDef) {

        if (hasVisibilityModifier) {
            return false;
        }

        if (isAnnotationDeclaration) {
            return true;
        }

        if (hasDef && hasReturnType) {
            return true;
        }

        if (hasModifier || hasAnnotation || !hasReturnType) {
            return true;
        }

        return false;
    }

    private boolean isBlankScript(CompilationUnitContext ctx) {
        long blankCnt =
                ctx.children.stream()
                        .filter(e -> e instanceof NlsContext
                                || e instanceof PackageDeclarationContext
                                || e instanceof SepContext
                                || e instanceof ImportStmtAltContext
                                || e instanceof TerminalNode && (((TerminalNode) e).getSymbol().getType() == EOF)
                        ).count();


        return blankCnt == ctx.children.size();
    }

    private void addEmptyReturnStatement() {
        moduleNode.addStatement(new ReturnStatement(new ConstantExpression(null)));
    }

    private ConstantExpression cleanStringLiteral(String text) {
        int slashyType = text.startsWith("/") ? StringUtil.SLASHY :
                text.startsWith("$/") ? StringUtil.DOLLAR_SLASHY : StringUtil.NONE_SLASHY;

        if (text.startsWith("'''") || text.startsWith("\"\"\"")) {
            text = StringUtil.removeCR(text); // remove CR in the multiline string

            text = text.length() == 6 ? "" : text.substring(3, text.length() - 3);
        } else if (text.startsWith("'") || text.startsWith("/") || text.startsWith("\"")) {
            text = text.length() == 2 ? "" : text.substring(1, text.length() - 1);
        } else if (text.startsWith("$/")) {
            text = StringUtil.removeCR(text);

            text = text.length() == 4 ? "" : text.substring(2, text.length() - 2);
        }

        //handle escapes.
        text = StringUtil.replaceEscapes(text, slashyType);

        ConstantExpression constantExpression = new ConstantExpression(text, true);
        constantExpression.putNodeMetaData(IS_STRING, true);

        return constantExpression;
    }

    private org.codehaus.groovy.syntax.Token createGroovyTokenByType(Token token, int type) {
        if (null == token) {
            throw new IllegalArgumentException("token should not be null");
        }

        return new org.codehaus.groovy.syntax.Token(type, token.getText(), token.getLine(), token.getCharPositionInLine());
    }

    /*
    private org.codehaus.groovy.syntax.Token createGroovyToken(TerminalNode node) {
        return this.createGroovyToken(node, 1);
    }
    */

    private org.codehaus.groovy.syntax.Token createGroovyToken(Token token) {
        return this.createGroovyToken(token, 1);
    }

    /**
     * @param node
     * @param cardinality Used for handling GT ">" operator, which can be repeated to give bitwise shifts >> or >>>
     * @return
     */
    private org.codehaus.groovy.syntax.Token createGroovyToken(TerminalNode node, int cardinality) {
        return this.createGroovyToken(node.getSymbol(), cardinality);
    }

    private org.codehaus.groovy.syntax.Token createGroovyToken(Token token, int cardinality) {
        String text = StringGroovyMethods.multiply((CharSequence) token.getText(), cardinality);
        return new org.codehaus.groovy.syntax.Token(
                "..<".equals(token.getText()) || "..".equals(token.getText())
                        ? Types.RANGE_OPERATOR
                        : Types.lookup(text, Types.ANY),
                text,
                token.getLine(),
                token.getCharPositionInLine() + 1
        );
    }


    /**
     * Sets location(lineNumber, colNumber, lastLineNumber, lastColumnNumber) for node using standard context information.
     * Note: this method is implemented to be closed over ASTNode. It returns same node as it received in arguments.
     *
     * @param astNode Node to be modified.
     * @param ctx     Context from which information is obtained.
     * @return Modified astNode.
     */
    private <T extends ASTNode> T configureAST(T astNode, GroovyParserRuleContext ctx) {
        Token start = ctx.getStart();
        Token stop = ctx.getStop();

        String stopText = stop.getText();
        int stopTextLength = 0;
        int newLineCnt = 0;
        if (asBoolean((Object) stopText)) {
            stopTextLength = stopText.length();
            newLineCnt = (int) StringUtil.countChar(stopText, '\n');
        }

        astNode.setLineNumber(start.getLine());
        astNode.setColumnNumber(start.getCharPositionInLine() + 1);

        if (0 == newLineCnt) {
            astNode.setLastLineNumber(stop.getLine());
            astNode.setLastColumnNumber(stop.getCharPositionInLine() + 1 + stop.getText().length());
        } else { // e.g. GStringEnd contains newlines, we should fix the location info
            astNode.setLastLineNumber(stop.getLine() + newLineCnt);
            astNode.setLastColumnNumber(stopTextLength - stopText.lastIndexOf('\n'));
        }

        return astNode;
    }

    private <T extends ASTNode> T configureAST(T astNode, TerminalNode terminalNode) {
        return this.configureAST(astNode, terminalNode.getSymbol());
    }

    private <T extends ASTNode> T configureAST(T astNode, Token token) {
        astNode.setLineNumber(token.getLine());
        astNode.setColumnNumber(token.getCharPositionInLine() + 1);
        astNode.setLastLineNumber(token.getLine());
        astNode.setLastColumnNumber(token.getCharPositionInLine() + 1 + token.getText().length());

        return astNode;
    }

    private <T extends ASTNode> T configureAST(T astNode, ASTNode source) {
        astNode.setLineNumber(source.getLineNumber());
        astNode.setColumnNumber(source.getColumnNumber());
        astNode.setLastLineNumber(source.getLastLineNumber());
        astNode.setLastColumnNumber(source.getLastColumnNumber());

        return astNode;
    }

    private boolean isTrue(ASTNode node, String key) {
        Object nmd = node.getNodeMetaData(key);

        if (null == nmd) {
            return false;
        }

        if (!(nmd instanceof Boolean)) {
            throw new GroovyBugError(node + " node meta data[" + key + "] is not an instance of Boolean");
        }

        return (Boolean) nmd;
    }

    private CompilationFailedException createParsingFailedException(String msg, GroovyParserRuleContext ctx) {
        return createParsingFailedException(
                new SyntaxException(msg,
                        ctx.start.getLine(),
                        ctx.start.getCharPositionInLine() + 1,
                        ctx.stop.getLine(),
                        ctx.stop.getCharPositionInLine() + 1 + ctx.stop.getText().length()));
    }

    private CompilationFailedException createParsingFailedException(String msg, ASTNode node) {
        return createParsingFailedException(
                new SyntaxException(msg,
                        node.getLineNumber(),
                        node.getColumnNumber(),
                        node.getLastLineNumber(),
                        node.getLastColumnNumber()));
    }

    /*
    private CompilationFailedException createParsingFailedException(String msg, Token token) {
        return createParsingFailedException(
                new SyntaxException(msg,
                        token.getLine(),
                        token.getCharPositionInLine() + 1,
                        token.getLine(),
                        token.getCharPositionInLine() + 1 + token.getText().length()));
    }
    */

    private CompilationFailedException createParsingFailedException(Exception e) {
        return new CompilationFailedException(
                CompilePhase.PARSING.getPhaseNumber(),
                this.sourceUnit,
                e);
    }

    private String readSourceCode(SourceUnit sourceUnit) {
        String text = null;
        try {
            text = IOGroovyMethods.getText(
                    new BufferedReader(
                            sourceUnit.getSource().getReader()));
        } catch (IOException e) {
            LOGGER.severe(createExceptionMessage(e));
            throw new RuntimeException("Error occurred when reading source code.", e);
        }

        return text;
    }

    private void setupErrorListener(GroovyLangParser parser) {
        parser.removeErrorListeners();
        parser.addErrorListener(new ANTLRErrorListener() {
            @Override
            public void syntaxError(
                    Recognizer<?, ?> recognizer,
                    Object offendingSymbol, int line, int charPositionInLine,
                    String msg, RecognitionException e) {

                sourceUnit.getErrorCollector().addFatalError(new SyntaxErrorMessage(new SyntaxException(msg, line, charPositionInLine + 1), sourceUnit));
            }

            @Override
            public void reportAmbiguity(Parser recognizer, DFA dfa, int startIndex, int stopIndex, boolean exact, BitSet ambigAlts, ATNConfigSet configs) {

                LOGGER.fine("Ambiguity at " + startIndex + " - " + stopIndex);
            }

            @Override
            public void reportAttemptingFullContext(
                    Parser recognizer,
                    DFA dfa, int startIndex, int stopIndex,
                    BitSet conflictingAlts, ATNConfigSet configs) {

                LOGGER.fine("Attempting Full Context at " + startIndex + " - " + stopIndex);
            }

            @Override
            public void reportContextSensitivity(
                    Parser recognizer,
                    DFA dfa, int startIndex, int stopIndex, int prediction, ATNConfigSet configs) {

                LOGGER.fine("Context Sensitivity at " + startIndex + " - " + stopIndex);
            }
        });
    }


    private String createExceptionMessage(Throwable t) {
        StringWriter sw = new StringWriter();

        try (PrintWriter pw = new PrintWriter(sw)) {
            t.printStackTrace(pw);
        }

        return sw.toString();
    }


    private class DeclarationListStatement extends Statement {
        private List<ExpressionStatement> declarationStatements;

        public DeclarationListStatement(DeclarationExpression... declarations) {
            this(Arrays.asList(declarations));
        }

        public DeclarationListStatement(List<DeclarationExpression> declarations) {
            this.declarationStatements =
                    declarations.stream()
                            .map(e -> configureAST(new ExpressionStatement(e), e))
                            .collect(Collectors.toList());
        }

        public List<ExpressionStatement> getDeclarationStatements() {
            List<String> declarationListStatementLabels = this.getStatementLabels();

            this.declarationStatements.stream().forEach(e -> {
                if (asBoolean((Object) declarationListStatementLabels)) {
                    // clear existing statement labels before setting labels
                    if (asBoolean((Object) e.getStatementLabels())) {
                        e.getStatementLabels().clear();
                    }

                    declarationListStatementLabels.stream().forEach(e::addStatementLabel);
                }
            });

            return this.declarationStatements;
        }

        public List<DeclarationExpression> getDeclarationExpressions() {
            return this.declarationStatements.stream()
                    .map(e -> (DeclarationExpression) e.getExpression())
                    .collect(Collectors.toList());
        }
    }

    private static class Pair<K, V> {
        private K key;
        private V value;

        public Pair(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K getKey() {
            return key;
        }

        public void setKey(K key) {
            this.key = key;
        }

        public V getValue() {
            return value;
        }

        public void setValue(V value) {
            this.value = value;
        }


        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Pair<?, ?> pair = (Pair<?, ?>) o;
            return Objects.equals(key, pair.key) &&
                    Objects.equals(value, pair.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, value);
        }
    }

    /**
     * Process modifiers for AST nodes
     * <p>
     * Created by Daniel.Sun on 2016/8/27.
     */
    private static class ModifierManager {
        private List<ModifierNode> modifierNodeList;

        public ModifierManager(List<ModifierNode> modifierNodeList) {
            this.modifierNodeList = Collections.unmodifiableList(modifierNodeList);
        }

        public List<AnnotationNode> getAnnotations() {
            return modifierNodeList.stream()
                    .filter(ModifierNode::isAnnotation)
                    .map(ModifierNode::getAnnotationNode)
                    .collect(Collectors.toList());
        }

        public boolean contains(int modifierType) {
            return modifierNodeList.stream().anyMatch(e -> modifierType == e.getType());
        }

        public boolean containsAnnotations() {
            return modifierNodeList.stream().anyMatch(ModifierNode::isAnnotation);
        }

        public boolean containsVisibilityModifier() {
            return modifierNodeList.stream().anyMatch(ModifierNode::isVisibilityModifier);
        }

        public boolean containsNonVisibilityModifier() {
            return modifierNodeList.stream().anyMatch(ModifierNode::isNonVisibilityModifier);
        }

        public Parameter processParameter(Parameter parameter) {
            modifierNodeList.forEach(e -> {
                parameter.setModifiers(parameter.getModifiers() | e.getOpCode());

                if (e.isAnnotation()) {
                    parameter.addAnnotation(e.getAnnotationNode());
                }
            });

            return parameter;
        }

        public VariableExpression processVariableExpression(VariableExpression ve) {
            modifierNodeList.forEach(e -> {
                ve.setModifiers(ve.getModifiers() | e.getOpCode());

                // local variable does not attach annotations
            });

            return ve;
        }

        public DeclarationExpression processDeclarationExpression(DeclarationExpression de) {
            this.getAnnotations().forEach(de::addAnnotation);

            return de;
        }

        public MethodNode processMethodNode(MethodNode mn) {
            modifierNodeList.forEach(e -> {
                mn.setModifiers(mn.getModifiers() | e.getOpCode());

                if (e.isAnnotation()) {
                    mn.addAnnotation(e.getAnnotationNode());
                }
            });

            return mn;
        }
    }

    /**
     * Represents a modifier, which is better to place in the package org.codehaus.groovy.ast
     * <p>
     * Created by Daniel.Sun on 2016/8/23.
     */
    private static class ModifierNode extends ASTNode {
        private Integer type;
        private Integer opCode; // ASM opcode
        private String text;
        private AnnotationNode annotationNode;

        public static final int ANNOTATION_TYPE = -999;
        private static final Map<Integer, Integer> MAP = new HashMap<Integer, Integer>() {
            {
                put(ANNOTATION_TYPE, 0);
                put(DEF, 0);

                put(NATIVE, Opcodes.ACC_NATIVE);
                put(SYNCHRONIZED, Opcodes.ACC_SYNCHRONIZED);
                put(TRANSIENT, Opcodes.ACC_TRANSIENT);
                put(VOLATILE, Opcodes.ACC_VOLATILE);

                put(PUBLIC, Opcodes.ACC_PUBLIC);
                put(PROTECTED, Opcodes.ACC_PROTECTED);
                put(PRIVATE, Opcodes.ACC_PRIVATE);
                put(STATIC, Opcodes.ACC_STATIC);
                put(ABSTRACT, Opcodes.ACC_ABSTRACT);
                put(FINAL, Opcodes.ACC_FINAL);
                put(STRICTFP, Opcodes.ACC_STRICT);
            }
        };

        public ModifierNode(Integer type) {
            this.type = type;
            this.opCode = MAP.get(type);

            if (!asBoolean((Object) this.opCode)) {
                throw new IllegalArgumentException("Unsupported modifier type: " + type);
            }
        }

        /**
         * @param type the modifier type, which is same as the token type
         * @param text text of the ast node
         */
        public ModifierNode(Integer type, String text) {
            this(type);
            this.text = text;
        }

        /**
         * @param annotationNode the annotation node
         * @param text           text of the ast node
         */
        public ModifierNode(AnnotationNode annotationNode, String text) {
            this(ModifierNode.ANNOTATION_TYPE, text);
            this.annotationNode = annotationNode;

            if (!asBoolean(annotationNode)) {
                throw new IllegalArgumentException("annotationNode can not be null");
            }
        }

        /**
         * Check whether the modifier is not an imagined modifier(annotation, def)
         */
        public boolean isModifier() {
            return !this.isAnnotation() && !this.isDef();
        }

        public boolean isVisibilityModifier() {
            return Objects.equals(PUBLIC, this.type)
                    || Objects.equals(PROTECTED, this.type)
                    || Objects.equals(PRIVATE, this.type);
        }

        public boolean isNonVisibilityModifier() {
            return this.isModifier() && !this.isVisibilityModifier();
        }

        public boolean isAnnotation() {
            return Objects.equals(ANNOTATION_TYPE, this.type);
        }

        public boolean isDef() {
            return Objects.equals(DEF, this.type);
        }

        public Integer getType() {
            return type;
        }

        public Integer getOpCode() {
            return opCode;
        }

        @Override
        public String getText() {
            return text;
        }

        public AnnotationNode getAnnotationNode() {
            return annotationNode;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ModifierNode that = (ModifierNode) o;
            return Objects.equals(type, that.type) &&
                    Objects.equals(text, that.text) &&
                    Objects.equals(annotationNode, that.annotationNode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, text, annotationNode);
        }
    }

    private final ModuleNode moduleNode;
    private final SourceUnit sourceUnit;
    private final GroovyLangLexer lexer;
    private final GroovyLangParser parser;
    private static final Class<ImportNode> IMPORT_NODE_CLASS = ImportNode.class;
    private static final String QUESTION_STR = "?";
    private static final String DOT_STR = ".";
    private static final String SUB_STR = "-";
    private static final String VALUE_STR = "value";
    private static final String DOLLAR_STR = "$";
    private static final String CALL_STR = "call";
    private static final String THIS_STR = "this";
    private static final String SUPER_STR = "super";
    private static final Set<String> PRIMITIVE_TYPE_SET = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("boolean", "char", "byte", "short", "int", "long", "float", "double")));
    private static final Logger LOGGER = Logger.getLogger(ASTBuilder.class.getName());

    // keys for meta data
    private static final String IS_INSIDE_PARENTHESES = "_IS_INSIDE_PARENTHESES";
    private static final String IS_SWITCH_DEFAULT = "_IS_SWITCH_DEFAULT";
    private static final String IS_NUMERIC = "_IS_NUMERIC";
    private static final String IS_STRING = "_IS_STRING";

    private static final String PATH_EXPRESSION_BASE_EXPR = "_PATH_EXPRESSION_BASE_EXPR";
    private static final String PATH_EXPRESSION_BASE_EXPR_GENERICS_TYPES = "_PATH_EXPRESSION_BASE_EXPR_GENERICS_TYPES";
    private static final String CMD_EXPRESSION_BASE_EXPR = "_CMD_EXPRESSION_BASE_EXPR";
}
