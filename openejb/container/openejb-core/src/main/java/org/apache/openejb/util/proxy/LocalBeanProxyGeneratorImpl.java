/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.openejb.util.proxy;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.xbean.asm.ClassWriter;
import org.apache.xbean.asm.FieldVisitor;
import org.apache.xbean.asm.Label;
import org.apache.xbean.asm.MethodVisitor;
import org.apache.xbean.asm.Opcodes;
import org.apache.xbean.asm.Type;

public class LocalBeanProxyGeneratorImpl implements LocalBeanProxyGenerator, Opcodes {
    private static final String[] SERIALIZABLE = new String[] { Serializable.class.getName().replace('.', '/') };

    static final String BUSSINESS_HANDLER_NAME = "businessHandler";    
    static final String NON_BUSINESS_HANDLER_NAME = "nonBusinessHandler";

    // sun.misc.Unsafe
    private static final Object unsafe;
    private static final Method defineClass;

    static {
        final Class<?> unsafeClass;
        try {
            unsafeClass = AccessController.doPrivileged(new PrivilegedAction<Class<?>>() {
                public Class<?> run() {
                    try {
                        return Thread.currentThread().getContextClassLoader().loadClass("sun.misc.Unsafe");
                    } catch (Exception e) {
                        try {
                            return ClassLoader.getSystemClassLoader().loadClass("sun.misc.Unsafe");
                        } catch (ClassNotFoundException e1) {
                            throw new IllegalStateException("Cannot get sun.misc.Unsafe", e);
                        }
                    }
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("Cannot get sun.misc.Unsafe class", e);
        }

        unsafe = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            public Object run() {
                try {
                    Field field = unsafeClass.getDeclaredField("theUnsafe");
                    field.setAccessible(true);
                    return field.get(null);
                } catch (Exception e) {
                    throw new IllegalStateException("Cannot get sun.misc.Unsafe", e);
                }
            }
        });
        defineClass = AccessController.doPrivileged(new PrivilegedAction<Method>() {
            public Method run() {
                try {
                    Method mtd = unsafeClass.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class, ClassLoader.class, ProtectionDomain.class);
                    mtd.setAccessible(true);
                    return mtd;
                } catch (Exception e) {
                    throw new IllegalStateException("Cannot get sun.misc.Unsafe.defineClass", e);
                }
            }
        });
    }

    public Class createProxy(Class<?> clsToProxy, ClassLoader cl) {
        String proxyName = generateProxyName(clsToProxy.getName());
        return createProxy(clsToProxy, proxyName, cl);
    }

    private static String generateProxyName(String clsName) {
        return clsName + "$LocalBeanProxy";
    }

    public static boolean isLocalBean(Class<?> clazz) {
        if (clazz.getSuperclass() == null) {
            return false;
        }
        return clazz.getName().equals(LocalBeanProxyGeneratorImpl.generateProxyName(clazz.getSuperclass().getName()));
    }

    private Class createProxy(Class<?> clsToProxy, String proxyName, ClassLoader cl) {
        String clsName = proxyName.replaceAll("\\.", "/");

        try {
            return cl.loadClass(proxyName);
        } catch (Exception e) {
        }

        synchronized (LocalBeanProxyGeneratorImpl.class) { // it can be done by concurrent threads
            try { // try it again
                return cl.loadClass(proxyName);
            } catch (Exception e) {
            }

            try {
                byte[] proxyBytes = generateProxy(clsToProxy, clsName);
                return (Class<?>) defineClass.invoke(unsafe, proxyName, proxyBytes, 0, proxyBytes.length, clsToProxy.getClassLoader(), clsToProxy.getProtectionDomain());
            } catch (Exception e) {
                throw new InternalError(e.toString());
            }
        }
    }

	private byte[] generateProxy(Class<?> clsToProxy, String proxyName) throws ProxyGenerationException {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		FieldVisitor fv;
		MethodVisitor mv;

		String clsToOverride = clsToProxy.getName().replaceAll("\\.", "/");
        String proxyClassName = proxyName.replaceAll("\\.", "/");

        // push class signature
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, proxyClassName, null, clsToOverride, SERIALIZABLE);
		cw.visitSource(clsToOverride + ".java", null);

		// push InvocationHandler fields
		fv = cw.visitField(ACC_FINAL + ACC_PRIVATE, BUSSINESS_HANDLER_NAME, "Ljava/lang/reflect/InvocationHandler;", null, null);
		fv.visitEnd();
		fv = cw.visitField(ACC_FINAL + ACC_PRIVATE, NON_BUSINESS_HANDLER_NAME, "Ljava/lang/reflect/InvocationHandler;", null, null);
		fv.visitEnd();
		
		// push single argument constructor
		mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/reflect/InvocationHandler;)V", null, null);
		mv.visitCode();
		mv.visitVarInsn(ALOAD, 0);
		mv.visitVarInsn(ALOAD, 1);
		mv.visitVarInsn(ALOAD, 1);
		mv.visitMethodInsn(INVOKESPECIAL, proxyName, "<init>", "(Ljava/lang/reflect/InvocationHandler;Ljava/lang/reflect/InvocationHandler;)V");
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		// push double argument constructor
		mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/reflect/InvocationHandler;Ljava/lang/reflect/InvocationHandler;)V", null, null);
		mv.visitCode();
		mv.visitVarInsn(ALOAD, 0);
		mv.visitMethodInsn(INVOKESPECIAL, clsToOverride, "<init>", "()V");
		mv.visitVarInsn(ALOAD, 0);
		mv.visitVarInsn(ALOAD, 1);
		mv.visitFieldInsn(PUTFIELD, proxyName, BUSSINESS_HANDLER_NAME, "Ljava/lang/reflect/InvocationHandler;");
		mv.visitVarInsn(ALOAD, 0);
		mv.visitVarInsn(ALOAD, 2);
		mv.visitFieldInsn(PUTFIELD, proxyName, NON_BUSINESS_HANDLER_NAME, "Ljava/lang/reflect/InvocationHandler;");
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		Map<String, List<Method>> methodMap = getNonPrivateMethods(new Class[] { clsToProxy });

		for (Map.Entry<String, List<Method>> entry : methodMap.entrySet()) {
		    for (Method method : entry.getValue()) {	
		        String name = method.getName();
                int modifiers = method.getModifiers();
                if (Modifier.isPublic(modifiers) ||
                    (method.getParameterTypes().length == 0 &&
                            ("finalize".equals(name) || "clone".equals(name)))) {
                    // forward invocations of any public methods or 
                    // finalize/clone methods to businessHandler 
                    processMethod(cw, method, proxyClassName, BUSSINESS_HANDLER_NAME);                    
                } else {
                    // forward invocations of any other methods to nonBusinessHandler
                    processMethod(cw, method, proxyClassName, NON_BUSINESS_HANDLER_NAME);
                }
		    }
		}
		
		byte[] clsBytes = cw.toByteArray();
		return clsBytes;
	}

	/*
	 * Return all protected, public, and default methods of a given class
	 * that are not final or static. The returned map includes the inherited methods
	 * and ensures that overridden methods are included once.
	 */
	private Map<String, List<Method>> getNonPrivateMethods(Class<?>[] classes) {
		Map<String, List<Method>> methodMap = new HashMap<String, List<Method>>();
		
		for (int i = 0; i < classes.length; i++) {
			Class<?> clazz = classes[i];
		    
	        while (clazz != null) {
	            for (Method method : clazz.getDeclaredMethods()) {
	                int modifiers = method.getModifiers();
	                if (Modifier.isFinal(modifiers) || 
	                    Modifier.isPrivate(modifiers) || 
	                    Modifier.isStatic(modifiers)) {
	                    continue;
	                }
	                
	                List<Method> methods = methodMap.get(method.getName());
	                if (methods == null) {
	                    methods = new ArrayList<Method>();
	                    methods.add(method);
	                    methodMap.put(method.getName(), methods);
	                } else {
	                    if (isOverridden(methods, method)) {
	                        // method is overridden in superclass, so do nothing
	                    } else {
	                        // method is not overridden, so add it
	                        methods.add(method);
	                    }
	                }
	            }
	            
	            clazz = clazz.getSuperclass();
	        }
		}
        return methodMap;
	}
	
	private boolean isOverridden(List<Method> methods, Method method) {
	    for (Method m : methods) {
	        if (Arrays.equals(m.getParameterTypes(), method.getParameterTypes())) {
	            return true;
	        }
	    }
	    return false;
	}

	private void processMethod(ClassWriter cw, Method method, String proxyName, String handlerName) throws ProxyGenerationException {
		if ("<init>".equals(method.getName())) {
			return;
		}
		
		Class<?> returnType = method.getReturnType();
		Class<?>[] parameterTypes = method.getParameterTypes();
		Class<?>[] exceptionTypes = method.getExceptionTypes();
		int modifiers = method.getModifiers();
		
		// push the method definition
		int modifier = 0;
		if (Modifier.isPublic(modifiers)) {
		    modifier = ACC_PUBLIC;
		} else if (Modifier.isProtected(modifiers)) {
		    modifier = ACC_PROTECTED;
		}
		MethodVisitor mv = cw.visitMethod(modifier, method.getName(), getMethodSignatureAsString(returnType, parameterTypes), null, null);
		mv.visitCode();

		// push try/catch block, to catch declared exceptions, and to catch java.lang.Throwable
		Label l0 = new Label();
		Label l1 = new Label();
		Label l2 = new Label();
		Label l3 = new Label();
		
		if (exceptionTypes.length > 0) {
			mv.visitTryCatchBlock(l0, l1, l2, "java/lang/reflect/InvocationTargetException");
		}
		
//		mv.visitTryCatchBlock(l0, l1, l3, "java/lang/Throwable");
		
		// push try code
		mv.visitLabel(l0);
		String clsToOverride = method.getDeclaringClass().getName().replaceAll("\\.", "/");
		mv.visitLdcInsn(Type.getType("L" + clsToOverride + ";"));
		
		// the following code generates the bytecode for this line of Java:
		// Method method = <proxy>.class.getMethod("add", new Class[] { <array of function argument classes> });

		// get the method name to invoke, and push to stack
		mv.visitLdcInsn(method.getName());
		
		// create the Class[]
		createArrayDefinition(mv, parameterTypes.length, Class.class);
		
		int length = 1;
		
		// push parameters into array
		for (int i = 0; i < parameterTypes.length; i++) {
			// keep copy of array on stack
			mv.visitInsn(DUP);
			
			Class<?> parameterType = parameterTypes[i];
			
			// push number onto stack
			pushIntOntoStack(mv, i);

			if (parameterType.isPrimitive()) {
				String wrapperType = getWrapperType(parameterType);
				mv.visitFieldInsn(GETSTATIC, wrapperType, "TYPE", "Ljava/lang/Class;");
			} else {
				mv.visitLdcInsn(Type.getType(getAsmTypeAsString(parameterType, true)));
			}
			
			mv.visitInsn(AASTORE);
			
			if (Long.TYPE.equals(parameterType) || Double.TYPE.equals(parameterType)) {
				length += 2;
			} else {
				length++;
			}
		}
		
		// invoke getMethod() with the method name and the array of types		
		mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
	
		// store the returned method for later
		mv.visitVarInsn(ASTORE, length);
		
		// the following code generates bytecode equivalent to:
		// return ((<returntype>) invocationHandler.invoke(this, method, new Object[] { <function arguments }))[.<primitive>Value()];
		
		Label l4 = new Label();
		mv.visitLabel(l4);
		mv.visitVarInsn(ALOAD, 0);
		
		// get the invocationHandler field from this class
		mv.visitFieldInsn(GETFIELD, proxyName, handlerName, "Ljava/lang/reflect/InvocationHandler;");
		
		// we want to pass "this" in as the first parameter
		mv.visitVarInsn(ALOAD, 0);
		
		// and the method we fetched earlier
		mv.visitVarInsn(ALOAD, length);
		
		// need to construct the array of objects passed in

		// create the Object[]
		createArrayDefinition(mv, parameterTypes.length, Object.class);

		int index = 1;
		// push parameters into array
		for (int i = 0; i < parameterTypes.length; i++) {
			// keep copy of array on stack
			mv.visitInsn(DUP);
			
			Class<?> parameterType = parameterTypes[i];
			
			// push number onto stack
			pushIntOntoStack(mv, i);

			if (parameterType.isPrimitive()) {
				String wrapperType = getWrapperType(parameterType);
				mv.visitVarInsn(getVarInsn(parameterType), index);
				
				mv.visitMethodInsn(INVOKESTATIC, wrapperType, "valueOf", "(" + getPrimitiveLetter(parameterType) + ")L" + wrapperType + ";");
				mv.visitInsn(AASTORE);
				
				if (Long.TYPE.equals(parameterType) || Double.TYPE.equals(parameterType)) {
					index += 2;
				} else {
					index++;
				}
			} else {
				mv.visitVarInsn(ALOAD, index);
				mv.visitInsn(AASTORE);
				index++;
			}
		}

		// invoke the invocationHandler
		mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/reflect/InvocationHandler", "invoke", "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;");
		
		// cast the result
		mv.visitTypeInsn(CHECKCAST, getCastType(returnType));
		
		if (returnType.isPrimitive() && (!Void.TYPE.equals(returnType))) {
			// get the primitive value		
			mv.visitMethodInsn(INVOKEVIRTUAL, getWrapperType(returnType), getPrimitiveMethod(returnType), "()" + getPrimitiveLetter(returnType));
		}
		
		// push return
		mv.visitLabel(l1);
		if (! Void.TYPE.equals(returnType)) {
			mv.visitInsn(getReturnInsn(returnType));
		} else {
			mv.visitInsn(POP);
			mv.visitInsn(RETURN);
		}

		// catch InvocationTargetException
		if (exceptionTypes.length > 0) {
			mv.visitLabel(l2);
			mv.visitVarInsn(ASTORE, length);
			Label l5 = new Label();
			mv.visitLabel(l5);
	
			for (int i = 0; i < exceptionTypes.length; i++) {
				Class<?> exceptionType = exceptionTypes[i];
				
				mv.visitLdcInsn(Type.getType("L" + exceptionType.getCanonicalName().replaceAll("\\.", "/") + ";"));
				mv.visitVarInsn(ALOAD, length);
				mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/InvocationTargetException", "getCause", "()Ljava/lang/Throwable;");
				mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;");
				mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z");
				Label l6 = new Label();
				mv.visitJumpInsn(IFEQ, l6);
				Label l7 = new Label();
				mv.visitLabel(l7);
				mv.visitVarInsn(ALOAD, length);
				mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/InvocationTargetException", "getCause", "()Ljava/lang/Throwable;");
				mv.visitTypeInsn(CHECKCAST, exceptionType.getCanonicalName().replaceAll("\\.", "/"));
				mv.visitInsn(ATHROW);
				mv.visitLabel(l6);
				
				if (i == (exceptionTypes.length - 1)) {
					mv.visitTypeInsn(NEW, "java/lang/reflect/UndeclaredThrowableException");
					mv.visitInsn(DUP);
					mv.visitVarInsn(ALOAD, length);
					mv.visitMethodInsn(INVOKESPECIAL, "java/lang/reflect/UndeclaredThrowableException", "<init>", "(Ljava/lang/Throwable;)V");
					mv.visitInsn(ATHROW);
				}
			}
		}

		// finish this method
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	/**
	 * Gets the appropriate bytecode instruction for RETURN, according to what type we need to return
	 * @param type Type the needs to be returned
	 * @return The matching bytecode instruction
	 */
	private int getReturnInsn(Class<?> type) {
		if (type.isPrimitive()) {
			if (Integer.TYPE.equals(type)) {
				return IRETURN;
			} else if (Boolean.TYPE.equals(type)) {
				return IRETURN;
			} else if (Character.TYPE.equals(type)) {
				return IRETURN;
			} else if (Byte.TYPE.equals(type)) {
				return IRETURN;
			} else if (Short.TYPE.equals(type)) {
				return IRETURN;
			} else if (Float.TYPE.equals(type)) {
				return FRETURN;
			} else if (Long.TYPE.equals(type)) {
				return LRETURN;
			} else if (Double.TYPE.equals(type)) {
				return DRETURN;
			}
		}
		
		return ARETURN;
	}


	/**
	 * Returns the appropriate bytecode instruction to load a value from a variable to the stack
	 * @param type Type to load
	 * @return Bytecode instruction to use
	 */
	private int getVarInsn(Class<?> type) {
		if (type.isPrimitive()) {
			if (Integer.TYPE.equals(type)) {
				return ILOAD;
			} else if (Boolean.TYPE.equals(type)) {
				return ILOAD;
			} else if (Character.TYPE.equals(type)) {
				return ILOAD;
			} else if (Byte.TYPE.equals(type)) {
				return ILOAD;
			} else if (Short.TYPE.equals(type)) {
				return ILOAD;
			} else if (Float.TYPE.equals(type)) {
				return FLOAD;
			} else if (Long.TYPE.equals(type)) {
				return LLOAD;
			} else if (Double.TYPE.equals(type)) {
				return DLOAD;
			}
		}
		
		throw new IllegalStateException("Type: " + type.getCanonicalName() + " is not a primitive type");
	}

	/**
	 * Returns the name of the Java method to call to get the primitive value from an Object - e.g. intValue for java.lang.Integer
	 * @param type Type whose primitive method we want to lookup
	 * @return The name of the method to use
	 */
	private String getPrimitiveMethod(Class<?> type) {
		if (Integer.TYPE.equals(type)) {
			return "intValue";
		} else if (Boolean.TYPE.equals(type)) {
			return "booleanValue";
		} else if (Character.TYPE.equals(type)) {
			return "charValue";
		} else if (Byte.TYPE.equals(type)) {
			return "byteValue";
		} else if (Short.TYPE.equals(type)) {
			return "shortValue";
		} else if (Float.TYPE.equals(type)) {
			return "floatValue";
		} else if (Long.TYPE.equals(type)) {
			return "longValue";
		} else if (Double.TYPE.equals(type)) {
			return "doubleValue";
		}
		
		throw new IllegalStateException("Type: " + type.getCanonicalName() + " is not a primitive type");
	}

	/**
	 * Gets the string to use for CHECKCAST instruction, returning the correct value for any type, including primitives and arrays
	 * @param returnType The type to cast to with CHECKCAST
	 * @return CHECKCAST parameter
	 */
	String getCastType(Class<?> returnType) {
		if (returnType.isPrimitive()) {
			return getWrapperType(returnType);
		} else {
			return getAsmTypeAsString(returnType, false);
		}
	}

	/**
	 * Returns the wrapper type for a primitive, e.g. java.lang.Integer for int
	 * @param type
	 * @return
	 */
	private String getWrapperType(Class<?> type) {
		if (Integer.TYPE.equals(type)) {
			return Integer.class.getCanonicalName().replaceAll("\\.", "/");
		} else if (Boolean.TYPE.equals(type)) {
			return Boolean.class.getCanonicalName().replaceAll("\\.", "/");
		} else if (Character.TYPE.equals(type)) {
			return Character.class.getCanonicalName().replaceAll("\\.", "/");
		} else if (Byte.TYPE.equals(type)) {
			return Byte.class.getCanonicalName().replaceAll("\\.", "/");
		} else if (Short.TYPE.equals(type)) {
			return Short.class.getCanonicalName().replaceAll("\\.", "/");
		} else if (Float.TYPE.equals(type)) {
			return Float.class.getCanonicalName().replaceAll("\\.", "/");
		} else if (Long.TYPE.equals(type)) {
			return Long.class.getCanonicalName().replaceAll("\\.", "/");
		} else if (Double.TYPE.equals(type)) {
			return Double.class.getCanonicalName().replaceAll("\\.", "/");
		} else if (Void.TYPE.equals(type)) {
			return Void.class.getCanonicalName().replaceAll("\\.", "/");
		}
		
		throw new IllegalStateException("Type: " + type.getCanonicalName() + " is not a primitive type");
	}

	/**
	 * Invokes the most appropriate bytecode instruction to put a number on the stack
	 * @param mv
	 * @param i
	 */
	private void pushIntOntoStack(MethodVisitor mv, int i) {
		if (i == 0) {
			mv.visitInsn(ICONST_0);	
		} else if (i == 1) {
			mv.visitInsn(ICONST_1);
		} else if (i == 2) {
			mv.visitInsn(ICONST_2);
		} else if (i == 3) {
			mv.visitInsn(ICONST_3);
		} else if (i == 4) {
			mv.visitInsn(ICONST_4);
		} else if (i == 5) {
			mv.visitInsn(ICONST_5);
		} else if (i > 5 && i <= 255) {
			mv.visitIntInsn(BIPUSH, i);
		} else {
			mv.visitIntInsn(SIPUSH, i);
		}
	}

	/**
	 * pushes an array of the specified size to the method visitor. The generated bytecode will leave
	 * the new array at the top of the stack.
	 * 
	 * @param mv MethodVisitor to use
	 * @param size Size of the array to create
	 * @param type Type of array to create
	 * @throws ProxyGenerationException 
	 */
	private void createArrayDefinition(MethodVisitor mv, int size, Class<?> type) throws ProxyGenerationException {
		// create a new array of java.lang.class (2)
		
		if (size < 0) {
			throw new ProxyGenerationException("Array size cannot be less than zero");
		}
		
		pushIntOntoStack(mv, size);
		
		mv.visitTypeInsn(ANEWARRAY, type.getCanonicalName().replaceAll("\\.", "/"));
	}


	String getMethodSignatureAsString(Class<?> returnType, Class<?>[] parameterTypes) {
		StringBuilder builder = new StringBuilder();
		builder.append("(");
		for (Class<?> parameterType : parameterTypes) {
			builder.append(getAsmTypeAsString(parameterType, true));
		}
		
		builder.append(")");
		builder.append(getAsmTypeAsString(returnType, true));
		
		return builder.toString();
	}

	/**
	 * Returns the single letter that matches the given primitive in bytecode instructions 
	 * @param type
	 * @return
	 */
	private String getPrimitiveLetter(Class<?> type) {
		if (Integer.TYPE.equals(type)) {
			return "I";
		} else if (Void.TYPE.equals(type)) {
			return "V";
		} else if (Boolean.TYPE.equals(type)) {
			return "Z";
		} else if (Character.TYPE.equals(type)) {
			return "C";
		} else if (Byte.TYPE.equals(type)) {
			return "B";
		} else if (Short.TYPE.equals(type)) {
			return "S";
		} else if (Float.TYPE.equals(type)) {
			return "F";
		} else if (Long.TYPE.equals(type)) {
			return "J";
		} else if (Double.TYPE.equals(type)) {
			return "D";
		}
		
		throw new IllegalStateException("Type: " + type.getCanonicalName() + " is not a primitive type");
	}

	/**
	 * Converts a class to a String suitable for ASM.
	 * @param parameterType Class to convert
	 * @param wrap True if a non-array object should be wrapped with L and ; - e.g. Ljava/lang/Integer;
	 * @return String to use for ASM
	 */
	public String getAsmTypeAsString(Class<?> parameterType, boolean wrap) {
		if (parameterType.isArray()) {
			if (parameterType.getComponentType().isPrimitive()) {
				Class<?> componentType = parameterType.getComponentType();
				return "[" + getPrimitiveLetter(componentType);
			} else {
				return "[" + getAsmTypeAsString(parameterType.getComponentType(), true);
			}
		} else {
			if (! parameterType.isPrimitive()) {
                String clsName = parameterType.getCanonicalName();

                if (parameterType.isMemberClass()) {
                    int lastDot = clsName.lastIndexOf(".");
                    clsName = clsName.substring(0, lastDot) + "$" + clsName.substring(lastDot + 1);
                }
                if (wrap) {
					return "L" + clsName.replaceAll("\\.", "/") + ";";
				} else {
					return clsName.replaceAll("\\.", "/");
				}
			} else {
				return getPrimitiveLetter(parameterType);
			}
		}
	}

}
