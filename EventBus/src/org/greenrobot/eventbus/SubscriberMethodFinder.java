/*
 * Copyright (C) 2012-2016 Markus Junginger, greenrobot (http://greenrobot.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.greenrobot.eventbus;

import org.greenrobot.eventbus.meta.SubscriberInfo;
import org.greenrobot.eventbus.meta.SubscriberInfoIndex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class SubscriberMethodFinder {
    /*
     * In newer class files, compilers may add methods. Those are called bridge or synthetic methods.
     * EventBus must ignore both. There modifiers are not public but defined in the Java class file format:
     * http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6-200-A.1
     */
    private static final int BRIDGE = 0x40;
    private static final int SYNTHETIC = 0x1000;

    private static final int MODIFIERS_IGNORE = Modifier.ABSTRACT | Modifier.STATIC | BRIDGE | SYNTHETIC;
    private static final Map<Class<?>, List<SubscriberMethod>> METHOD_CACHE = new ConcurrentHashMap<>();

    private List<SubscriberInfoIndex> subscriberInfoIndexes;
    private final boolean strictMethodVerification;
    private final boolean ignoreGeneratedIndex;

    private static final int POOL_SIZE = 4;
    private static final FindState[] FIND_STATE_POOL = new FindState[POOL_SIZE];

    SubscriberMethodFinder(List<SubscriberInfoIndex> subscriberInfoIndexes, boolean strictMethodVerification,
                           boolean ignoreGeneratedIndex) {
        this.subscriberInfoIndexes = subscriberInfoIndexes;
        this.strictMethodVerification = strictMethodVerification;
        this.ignoreGeneratedIndex = ignoreGeneratedIndex;
    }

    /**
     * 找到订阅方法
     *
     * @param subscriberClass 订阅者类
     * @return 订阅方法列表
     */
    List<SubscriberMethod> findSubscriberMethods(Class<?> subscriberClass) {
        //先从缓存里找订阅方法
        //METHOD_CACHE -> Map<Class<?>, List<SubscriberMethod>>: key为订阅者类，value为订阅方法列表
        List<SubscriberMethod> subscriberMethods = METHOD_CACHE.get(subscriberClass);
        if (subscriberMethods != null) {
            //如果缓存里有，直接返回使用
            return subscriberMethods;
        }

        //是否使用 subscriber index，ignoreGeneratedIndex默认为false
        if (ignoreGeneratedIndex) {
            subscriberMethods = findUsingReflection(subscriberClass);
        } else {
            subscriberMethods = findUsingInfo(subscriberClass);
        }
        if (subscriberMethods.isEmpty()) {
            //如果没有找到任何订阅方法，抛出异常，提醒用户使用 @Subscribe 方法来声明订阅方法
            //也就是说，如果用户register注册了，但是没有任何@Subscribe订阅方法，会抛出异常来提示用户
            throw new EventBusException("Subscriber " + subscriberClass + " and its super classes have no public methods with the @Subscribe annotation");
        } else {
            //如果订阅方法不为空，放入缓存中，以方便下次复用，key为订阅类的类名
            METHOD_CACHE.put(subscriberClass, subscriberMethods);
            return subscriberMethods;
        }
    }

    private List<SubscriberMethod> findUsingInfo(Class<?> subscriberClass) {
        //从FindState池中获取一个FindState对象并进行初始化
        FindState findState = prepareFindState();
        findState.initForSubscriber(subscriberClass);
        //这里使用了一个while循环，表示子类查找完了，会去父类继续查找
        while (findState.clazz != null) {
            //去 index 文件中查找订阅信息
            findState.subscriberInfo = getSubscriberInfo(findState);
            if (findState.subscriberInfo != null) {
                //遍历订阅方法
                SubscriberMethod[] array = findState.subscriberInfo.getSubscriberMethods();
                for (SubscriberMethod subscriberMethod : array) {
                    //检查是否已经添加了该订阅方法
                    if (findState.checkAdd(subscriberMethod.method, subscriberMethod.eventType)) {
                        //未添加，将找到的订阅方法添加到订阅方法列表中
                        findState.subscriberMethods.add(subscriberMethod);
                    }
                }
            } else {
                //如果EventBusIndex返回的订阅方法为空，则使用反射方法来查找订阅方法
                findUsingReflectionInSingleClass(findState);
            }
            //查找父类
            findState.moveToSuperclass();
        }
        //返回订阅方法列表
        return getMethodsAndRelease(findState);
    }

    private List<SubscriberMethod> getMethodsAndRelease(FindState findState) {
        List<SubscriberMethod> subscriberMethods = new ArrayList<>(findState.subscriberMethods);
        findState.recycle();
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                if (FIND_STATE_POOL[i] == null) {
                    FIND_STATE_POOL[i] = findState;
                    break;
                }
            }
        }
        return subscriberMethods;
    }

    private FindState prepareFindState() {
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                FindState state = FIND_STATE_POOL[i];
                if (state != null) {
                    FIND_STATE_POOL[i] = null;
                    return state;
                }
            }
        }
        return new FindState();
    }

    private SubscriberInfo getSubscriberInfo(FindState findState) {
        //subscriberInfo 不为空，表示已经找到了订阅信息，则这次需要往父类查找
        if (findState.subscriberInfo != null && findState.subscriberInfo.getSuperSubscriberInfo() != null) {
            SubscriberInfo superclassInfo = findState.subscriberInfo.getSuperSubscriberInfo();
            //确定此次查找的正是父类
            if (findState.clazz == superclassInfo.getSubscriberClass()) {
                return superclassInfo;
            }
        }
        //subscriberInfoIndexes 就是 EventBus.addIndex(MyEventBusIndex()) 加进来的
        if (subscriberInfoIndexes != null) {
            for (SubscriberInfoIndex index : subscriberInfoIndexes) {
                //就是执行 MyEventBusIndex 类中的 getSubscriberInfo 方法，来获取订阅信息
                SubscriberInfo info = index.getSubscriberInfo(findState.clazz);
                if (info != null) {
                    return info;
                }
            }
        }
        return null;
    }

    private List<SubscriberMethod> findUsingReflection(Class<?> subscriberClass) {
        FindState findState = prepareFindState();
        findState.initForSubscriber(subscriberClass);
        while (findState.clazz != null) {
            findUsingReflectionInSingleClass(findState);
            //查找父类，具体执行要看 skipSuperClasses 标志位
            findState.moveToSuperclass();
        }
        //返回订阅方法列表
        return getMethodsAndRelease(findState);
    }

    /**
     * 通过类的反射提取订阅信息
     *
     * @param findState
     */
    private void findUsingReflectionInSingleClass(FindState findState) {
        Method[] methods;
        try {
            // This is faster than getMethods, especially when subscribers are fat classes like Activities
            // getMethods(): 返回由类或接口声明的以及从超类和超接口继承的所有公共方法。
            // getDeclaredMethods(): 返回类声明的方法，包括 public, protected, default (package)，但不包括继承的方法
            // 所以，相对比于 getMethods 方法，该方法速度更加快，尤其是在复杂的类中，如 Activity。
            methods = findState.clazz.getDeclaredMethods();
        } catch (Throwable th) {
            // Workaround for java.lang.NoClassDefFoundError, see https://github.com/greenrobot/EventBus/issues/149
            try {
                methods = findState.clazz.getMethods();
            } catch (LinkageError error) { // super class of NoClassDefFoundError to be a bit more broad...
                String msg = "Could not inspect methods of " + findState.clazz.getName();
                if (ignoreGeneratedIndex) {
                    //请考虑使用 EventBus annotation processor 来避免反射
                    msg += ". Please consider using EventBus annotation processor to avoid reflection.";
                } else {
                    //找不到该类中方法，抛出异常
                    msg += ". Please make this class visible to EventBus annotation processor to avoid reflection.";
                }
                throw new EventBusException(msg, error);
            }
            // 因为getMethods()方法已经获取了超类的方法，所以这里设置不再去检查超类
            findState.skipSuperClasses = true;
        }
        for (Method method : methods) {
            //获取方法修饰符: public->1;private->2;protected->4;static->8;final->16
            int modifiers = method.getModifiers();
            //如果是public，且不是 abstract | static 类的
            if ((modifiers & Modifier.PUBLIC) != 0 && (modifiers & MODIFIERS_IGNORE) == 0) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length == 1) {
                    //获取方法参数类型
                    Subscribe subscribeAnnotation = method.getAnnotation(Subscribe.class);
                    if (subscribeAnnotation != null) {
                        //第一个参数就是事件类型
                        Class<?> eventType = parameterTypes[0];
                        //检查是否已经添加了订阅该类型事件的订阅方法，true->没有添加；false->已添加
                        if (findState.checkAdd(method, eventType)) {
                            ThreadMode threadMode = subscribeAnnotation.threadMode();
                            //没有添加过，根据找到的参数来新建一个订阅方法对象，加入 subscriberMethods 列表中
                            findState.subscriberMethods.add(new SubscriberMethod(method, eventType, threadMode, subscribeAnnotation.priority(), subscribeAnnotation.sticky()));
                        }
                    }
                } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                    String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                    throw new EventBusException("@Subscribe method " + methodName +
                            "must have exactly 1 parameter but has " + parameterTypes.length);
                }
            } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                throw new EventBusException(methodName +
                        " is a illegal @Subscribe method: must be public, non-static, and non-abstract");
            }
        }
    }

    static void clearCaches() {
        METHOD_CACHE.clear();
    }

    static class FindState {
        final List<SubscriberMethod> subscriberMethods = new ArrayList<>();
        final Map<Class, Object> anyMethodByEventType = new HashMap<>();
        final Map<String, Class> subscriberClassByMethodKey = new HashMap<>();
        final StringBuilder methodKeyBuilder = new StringBuilder(128);

        Class<?> subscriberClass;
        Class<?> clazz;
        boolean skipSuperClasses;
        SubscriberInfo subscriberInfo;

        void initForSubscriber(Class<?> subscriberClass) {
            this.subscriberClass = clazz = subscriberClass;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        void recycle() {
            subscriberMethods.clear();
            anyMethodByEventType.clear();
            subscriberClassByMethodKey.clear();
            methodKeyBuilder.setLength(0);
            subscriberClass = null;
            clazz = null;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        boolean checkAdd(Method method, Class<?> eventType) {
            // 2 level check: 1st level with event type only (fast), 2nd level with complete signature when required.
            // Usually a subscriber doesn't have methods listening to the same event type.
            // 通常情况下，一个订阅者不会有多个订阅方法来订阅同一个类型事件
            Object existing = anyMethodByEventType.put(eventType, method);
            if (existing == null) {
                //表示还没有存在订阅该类型事件的订阅方法
                return true;
            } else {
                //已经存在订阅该类型事件的订阅方法了
                //existing就是先存入anyMethodByEventType的订阅统一类型事件的订阅方法
                if (existing instanceof Method) {
                    if (!checkAddWithMethodSignature((Method) existing, eventType)) {
                        // Paranoia check
                        throw new IllegalStateException();
                    }
                    // Put any non-Method object to "consume" the existing Method
                    anyMethodByEventType.put(eventType, this);
                }
                return checkAddWithMethodSignature(method, eventType);
            }
        }

        private boolean checkAddWithMethodSignature(Method method, Class<?> eventType) {
            methodKeyBuilder.setLength(0);
            methodKeyBuilder.append(method.getName());
            methodKeyBuilder.append('>').append(eventType.getName());

            // "方法名>事件类型"
            // 意图：这样如果存在在同一个类中，有多个订阅方法订阅了同一个事件，这样当这个事件分发的时候，所有的订阅方法都会收到该事件。
            String methodKey = methodKeyBuilder.toString();
            Class<?> methodClass = method.getDeclaringClass();
            Class<?> methodClassOld = subscriberClassByMethodKey.put(methodKey, methodClass);
            /* 扩充：isAssignableFrom() 方法说明：
             * 当前的Class对象所表示的类，是不是参数中传递的Class对象所表示的类的父类，超接口，或者是相同的类型。
             * 是则返回true，否则返回false。
             */
            //methodClassOld 为空，表示没有添加过，或者是methodClassOld是methodClass的父类
            if (methodClassOld == null || methodClassOld.isAssignableFrom(methodClass)) {
                // Only add if not already found in a sub class
                // 只有在子类中没有找到的情况下才添加
                return true;
            } else {
                // Revert the put, old class is further down the class hierarchy
                subscriberClassByMethodKey.put(methodKey, methodClassOld);
                return false;
            }
        }

        void moveToSuperclass() {
            if (skipSuperClasses) {
                clazz = null;
            } else {
                clazz = clazz.getSuperclass();
                String clazzName = clazz.getName();
                // Skip system classes, this degrades performance.
                // Also we might avoid some ClassNotFoundException (see FAQ for background).
                if (clazzName.startsWith("java.") || clazzName.startsWith("javax.") ||
                        clazzName.startsWith("android.") || clazzName.startsWith("androidx.")) {
                    clazz = null;
                }
            }
        }
    }

}
