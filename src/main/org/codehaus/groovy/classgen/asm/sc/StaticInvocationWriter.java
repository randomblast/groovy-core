/*
 * Copyright 2003-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.classgen.asm.sc;

import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.classgen.AsmClassGenerator;
import org.codehaus.groovy.classgen.BytecodeExpression;
import org.codehaus.groovy.classgen.asm.*;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.transform.stc.ExtensionMethodNode;
import org.codehaus.groovy.transform.stc.StaticTypeCheckingSupport;
import org.codehaus.groovy.transform.stc.StaticTypeCheckingVisitor;
import org.codehaus.groovy.transform.stc.StaticTypesMarker;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.Opcodes.ACONST_NULL;

public class StaticInvocationWriter extends InvocationWriter {
    private static final ClassNode INVOKERHELPER_CLASSNODE = ClassHelper.make(InvokerHelper.class);
    private static final Expression INVOKERHELER_RECEIVER = new ClassExpression(INVOKERHELPER_CLASSNODE);
    private static final MethodNode INVOKERHELPER_INVOKEMETHOD = INVOKERHELPER_CLASSNODE.getMethod(
            "invokeMethodSafe",
            new Parameter[] {
                    new Parameter(ClassHelper.OBJECT_TYPE, "object"),
                    new Parameter(ClassHelper.STRING_TYPE, "name"),
                    new Parameter(ClassHelper.OBJECT_TYPE, "args")
            }
    );
    
    private static final MethodNode INVOKERHELPER_INVOKESTATICMETHOD = INVOKERHELPER_CLASSNODE.getMethod(
            "invokeStaticMethod",
            new Parameter[] {
                    new Parameter(ClassHelper.CLASS_Type, "clazz"),
                    new Parameter(ClassHelper.STRING_TYPE, "name"),
                    new Parameter(ClassHelper.OBJECT_TYPE, "args")
            }
    );
    private static final ClassNode ARRAYLIST_CLASSNODE = ClassHelper.make(ArrayList.class);
    private static final MethodNode ARRAYLIST_CONSTRUCTOR;
    private static final MethodNode ARRAYLIST_ADD_METHOD = ARRAYLIST_CLASSNODE.getMethod("add", new Parameter[]{new Parameter(ClassHelper.OBJECT_TYPE,"o")});

    static {
        ARRAYLIST_CONSTRUCTOR = new ConstructorNode(ACC_PUBLIC, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, EmptyStatement.INSTANCE);
        ARRAYLIST_CONSTRUCTOR.setDeclaringClass(ARRAYLIST_CLASSNODE);
    }

    private final AtomicInteger labelCounter = new AtomicInteger();
    
    private final WriterController controller;
    
    public StaticInvocationWriter(WriterController wc) {
        super(wc);
        controller = wc;
    }

    @Override
    public void writeInvokeConstructor(final ConstructorCallExpression call) {
        MethodNode mn = (MethodNode) call.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET);
        if (mn==null) {
            super.writeInvokeConstructor(call);
            return;
        }
        ConstructorNode cn;
        if (mn instanceof ConstructorNode) {
            cn = (ConstructorNode) mn;
        } else {
            cn = new ConstructorNode(mn.getModifiers(), mn.getParameters(), mn.getExceptions(), mn.getCode());
            cn.setDeclaringClass(mn.getDeclaringClass());
        }

        String ownerDescriptor = prepareConstructorCall(cn);
        TupleExpression args = makeArgumentList(call.getArguments());
        int before = controller.getOperandStack().getStackLength();
        loadArguments(args.getExpressions(), cn.getParameters());
        finnishConstructorCall(cn, ownerDescriptor, controller.getOperandStack().getStackLength()-before);

    }

    @Override
    protected boolean writeDirectMethodCall(final MethodNode target, final boolean implicitThis, final Expression receiver, final TupleExpression args) {
        if (target instanceof ExtensionMethodNode) {            
            MethodNode node = ((ExtensionMethodNode)target).getExtensionMethodNode();
            String methodName = target.getName();

            MethodVisitor mv = controller.getMethodVisitor();
            int argumentsToRemove = 0;
            List<Expression> argumentList = new LinkedList<Expression> (args.getExpressions());
            argumentList.add(0, receiver);
            Parameter[] parameters = node.getParameters();
            loadArguments(argumentList, parameters);

            String owner = BytecodeHelper.getClassInternalName(node.getDeclaringClass());
            String desc = BytecodeHelper.getMethodDescriptor(target.getReturnType(), parameters);
            mv.visitMethodInsn(INVOKESTATIC, owner, methodName, desc);
            ClassNode ret = target.getReturnType().redirect();
            if (ret== ClassHelper.VOID_TYPE) {
                ret = ClassHelper.OBJECT_TYPE;
                mv.visitInsn(ACONST_NULL);
            }
            argumentsToRemove += argumentList.size();
            controller.getOperandStack().remove(argumentsToRemove);
            controller.getOperandStack().push(ret);
            return true;
        } else {
            if (target == StaticTypeCheckingVisitor.CLOSURE_CALL_VARGS) {
                // wrap arguments into an array
                ArrayExpression arr = new ArrayExpression(ClassHelper.OBJECT_TYPE, args.getExpressions());
                return super.writeDirectMethodCall(target, implicitThis, receiver, new ArgumentListExpression(arr));
            }
            if (target!=null
                    && controller.getClassNode().isDerivedFrom(ClassHelper.CLOSURE_TYPE)
                    && controller.isInClosure()
                    && !target.isPublic()
                    && target.getDeclaringClass()!=controller.getClassNode()) {
                // replace call with an invoker helper call
                // todo: use MOP generated methods instead
                ArrayExpression arr = new ArrayExpression(ClassHelper.OBJECT_TYPE, args.getExpressions());
                MethodCallExpression mce = new MethodCallExpression(
                        INVOKERHELER_RECEIVER,
                        target.isStatic() ? "invokeStaticMethod" : "invokeMethodSafe",
                        new ArgumentListExpression(
                                receiver,
                                new ConstantExpression(target.getName()),
                                arr
                        )
                );
                mce.setMethodTarget(target.isStatic() ? INVOKERHELPER_INVOKESTATICMETHOD : INVOKERHELPER_INVOKEMETHOD);
                mce.visit(controller.getAcg());
                return true;
            }
            return super.writeDirectMethodCall(target, implicitThis, receiver, args);
        }
    }

    protected void loadArguments(List<Expression> argumentList, Parameter[] para) {
        if (para.length==0) return;
        ClassNode lastParaType = para[para.length - 1].getOriginType();
        AsmClassGenerator acg = controller.getAcg();
        OperandStack operandStack = controller.getOperandStack();
        if (lastParaType.isArray()
                && (argumentList.size()>para.length || argumentList.size()==para.length-1 || !argumentList.get(para.length-1).getType().isArray())) {
            int stackLen = operandStack.getStackLength()+argumentList.size();
            MethodVisitor mv = controller.getMethodVisitor();
            MethodVisitor orig = mv;
            //mv = new org.objectweb.asm.util.TraceMethodVisitor(mv);
            controller.setMethodVisitor(mv);
            // varg call
            // first parameters as usual
            for (int i = 0; i < para.length-1; i++) {
                argumentList.get(i).visit(acg);
                operandStack.doGroovyCast(para[i].getType());
            }
            // last parameters wrapped in an array
            List<Expression> lastParams = new LinkedList<Expression>();
            for (int i=para.length-1; i<argumentList.size();i++) {
                lastParams.add(argumentList.get(i));
            }
            ArrayExpression array = new ArrayExpression(
                    lastParaType.getComponentType(),
                    lastParams
            );
            array.visit(acg);
            // adjust stack length
            while (operandStack.getStackLength()<stackLen) {
                operandStack.push(ClassHelper.OBJECT_TYPE);
            }
            if (argumentList.size()==para.length-1) {
                operandStack.remove(1);
            }
        } else if (argumentList.size()==para.length) {
            for (int i = 0; i < argumentList.size(); i++) {
                argumentList.get(i).visit(acg);
                operandStack.doGroovyCast(para[i].getType());
            }
        } else {
            // method call with default arguments
            TypeChooser typeChooser = controller.getTypeChooser();
            ClassNode classNode = controller.getClassNode();
            Expression[] arguments = new Expression[para.length];
            for (int i=0, j=0 ; i<para.length;i++) {
                Parameter curParam = para[i];
                ClassNode curParamType = curParam.getType();
                Expression curArg = j<argumentList.size()?argumentList.get(j):null;
                Expression initialExpression = (Expression) curParam.getNodeMetaData(StaticTypesMarker.INITIAL_EXPRESSION);
                if (initialExpression==null && curParam.hasInitialExpression()) initialExpression = curParam.getInitialExpression();
                ClassNode curArgType = curArg==null?null:typeChooser.resolveType(curArg, classNode);

                if (initialExpression!=null && !compatibleArgumentType(curArgType, curParamType)) {
                    // use default expression
                    arguments[i] = initialExpression;
                } else {
                    arguments[i] = curArg;
                    j++;
                }
            }
            for (int i = 0; i < arguments.length; i++) {
                arguments[i].visit(acg);
                operandStack.doGroovyCast(para[i].getType());
            }
        }
    }

    private boolean compatibleArgumentType(ClassNode argumentType, ClassNode paramType) {
        if (argumentType==null) return false;
        if (ClassHelper.getWrapper(argumentType).equals(ClassHelper.getWrapper(paramType))) return true;
        if (paramType.isInterface()) return argumentType.implementsInterface(paramType);
        if (paramType.isArray() && argumentType.isArray()) return compatibleArgumentType(argumentType.getComponentType(),paramType.getComponentType());
        return ClassHelper.getWrapper(argumentType).isDerivedFrom(ClassHelper.getWrapper(paramType));
    }

    @Override
    public void makeCall(final Expression origin, final Expression receiver, final Expression message, final Expression arguments, final MethodCallerMultiAdapter adapter, final boolean safe, final boolean spreadSafe, final boolean implicitThis) {
        // if call is spread safe, replace it with a for in loop
        if (spreadSafe && origin instanceof MethodCallExpression) {
            MethodVisitor mv = controller.getMethodVisitor();
            CompileStack compileStack = controller.getCompileStack();
            TypeChooser typeChooser = controller.getTypeChooser();
            OperandStack operandStack = controller.getOperandStack();
            ClassNode classNode = controller.getClassNode();
            int counter = labelCounter.incrementAndGet();

            // create an empty arraylist
            VariableExpression result = new VariableExpression(
                    "spreadresult"+counter,
                    ARRAYLIST_CLASSNODE
            );
            ConstructorCallExpression cce = new ConstructorCallExpression(ARRAYLIST_CLASSNODE, ArgumentListExpression.EMPTY_ARGUMENTS);
            cce.setNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET, ARRAYLIST_CONSTRUCTOR);
            DeclarationExpression declr = new DeclarationExpression(
                    result,
                    Token.newSymbol("=", origin.getLineNumber(), origin.getColumnNumber()),
                    cce
            );
            declr.visit(controller.getAcg());
            // if (receiver != null)
            receiver.visit(controller.getAcg());
            Label ifnull = compileStack.createLocalLabel("ifnull_" + counter);
            mv.visitJumpInsn(IFNULL, ifnull);
            operandStack.remove(1); // receiver consumed by if()
            Label nonull = compileStack.createLocalLabel("nonull_" + counter);
            mv.visitLabel(nonull);
            ClassNode componentType = StaticTypeCheckingVisitor.inferLoopElementType(typeChooser.resolveType(receiver, classNode));
            Parameter iterator = new Parameter(componentType, "for$it$" + counter);
            VariableExpression iteratorAsVar = new VariableExpression(iterator);
            MethodCallExpression origMCE = (MethodCallExpression) origin;
            MethodCallExpression newMCE = new MethodCallExpression(
                    iteratorAsVar,
                    origMCE.getMethodAsString(),
                    origMCE.getArguments()
            );
            newMCE.setMethodTarget(origMCE.getMethodTarget());
            newMCE.setSafe(true);
            MethodCallExpression add = new MethodCallExpression(
                    result,
                    "add",
                    newMCE
            );
            add.setMethodTarget(ARRAYLIST_ADD_METHOD);
            // for (e in receiver) { result.add(e?.method(arguments) }
            ForStatement stmt = new ForStatement(
                    iterator,
                    receiver,
                    new ExpressionStatement(add)
            );
            stmt.visit(controller.getAcg());
            // else { empty list }
            mv.visitLabel(ifnull);

            // end of if/else
            // return result list
            result.visit(controller.getAcg());
        } else if (safe && origin instanceof MethodCallExpression) {
            // wrap call in an IFNULL check
            MethodVisitor mv = controller.getMethodVisitor();
            CompileStack compileStack = controller.getCompileStack();
            OperandStack operandStack = controller.getOperandStack();
            int counter = labelCounter.incrementAndGet();
            // if (receiver != null)
            receiver.visit(controller.getAcg());
            Label ifnull = compileStack.createLocalLabel("ifnull_" + counter);
            mv.visitJumpInsn(IFNULL, ifnull);
            operandStack.remove(1); // receiver consumed by if()
            Label nonull = compileStack.createLocalLabel("nonull_" + counter);
            mv.visitLabel(nonull);
            MethodCallExpression origMCE = (MethodCallExpression) origin;
            MethodCallExpression newMCE = new MethodCallExpression(
                    origMCE.getObjectExpression(),
                    origMCE.getMethodAsString(),
                    origMCE.getArguments()
            );
            newMCE.setMethodTarget(origMCE.getMethodTarget());
            newMCE.setSafe(false);
            newMCE.visit(controller.getAcg());
            Label endof = compileStack.createLocalLabel("endof_" + counter);
            mv.visitJumpInsn(GOTO,endof);
            mv.visitLabel(ifnull);
            // else { null }
            mv.visitInsn(ACONST_NULL);
            mv.visitLabel(endof);
        } else {
            super.makeCall(origin, receiver, message, arguments, adapter, safe, spreadSafe, implicitThis);
        }
    }
}