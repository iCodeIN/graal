/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.nodes.interop;

import java.util.function.IntFunction;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.espresso.EspressoLanguage;
import com.oracle.truffle.espresso.impl.ArrayKlass;
import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.impl.PrimitiveKlass;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.StaticObject;
import com.oracle.truffle.espresso.vm.InterpreterToVM;

@GenerateUncached
public abstract class ToEspressoNode extends Node {
    static final int LIMIT = 2;

    public abstract Object execute(Object value, Klass targetType) throws UnsupportedTypeException;

    @Specialization(guards = "cachedKlass == primitiveKlass", limit = "LIMIT")
    Object doPrimitive(Object value,
                    PrimitiveKlass primitiveKlass,
                    @CachedLibrary("value") InteropLibrary interop,
                    @Cached("primitiveKlass") PrimitiveKlass cachedKlass,
                    @Cached BranchProfile exceptionProfile) throws UnsupportedTypeException {
        try {
            switch (cachedKlass.getJavaKind()) {
                case Boolean:
                    if (interop.isBoolean(value)) {
                        return interop.asBoolean(value);
                    }
                    break;
                case Byte:
                    if (interop.fitsInByte(value)) {
                        return interop.asByte(value);
                    }
                    break;
                case Short:
                    if (interop.fitsInShort(value)) {
                        return interop.asShort(value);
                    }
                    break;
                case Char:
                    if (interop.isString(value)) {
                        String str = interop.asString(value);
                        if (str.length() == 1) {
                            return str.charAt(0);
                        }
                    }
                    break;
                case Int:
                    if (interop.fitsInInt(value)) {
                        return interop.asInt(value);
                    }
                    break;
                case Float:
                    if (interop.fitsInFloat(value)) {
                        return interop.asFloat(value);
                    }
                    break;
                case Long:
                    if (interop.fitsInLong(value)) {
                        return interop.asLong(value);
                    }
                    break;
                case Double:
                    if (interop.fitsInDouble(value)) {
                        return interop.asDouble(value);
                    }
                    break;
            }
        } catch (UnsupportedMessageException e) {
            exceptionProfile.enter();
            throw EspressoError.shouldNotReachHere("Contract violation: if fitsIn{type} returns true, as{type} must succeed.");
        }
        exceptionProfile.enter();
        throw UnsupportedTypeException.create(new Object[]{value}, primitiveKlass.getTypeAsString());
    }

    @TruffleBoundary
    @Specialization(replaces = "doPrimitive")
    Object doPrimitiveUncached(Object value, PrimitiveKlass primitiveKlass) throws UnsupportedTypeException {
        return doPrimitive(value, primitiveKlass, InteropLibrary.getFactory().getUncached(value), primitiveKlass, BranchProfile.getUncached());
    }

    @Specialization(guards = "!klass.isPrimitive()")
    Object doEspresso(StaticObject value,
                    Klass klass,
                    @Cached BranchProfile exceptionProfile) throws UnsupportedTypeException {
        // TODO(peterssen): Use a node for the instanceof check.
        if (StaticObject.isNull(value) || InterpreterToVM.instanceOf(value, klass)) {
            return value; // pass through, NULL coercion not needed.
        }
        exceptionProfile.enter();
        throw UnsupportedTypeException.create(new Object[]{value}, klass.getTypeAsString());
    }

    static boolean isStaticObject(Object obj) {
        return obj instanceof StaticObject;
    }

    static boolean isString(Klass klass) {
        return klass.getMeta().java_lang_String.equals(klass);
    }

    static boolean isStringArray(Klass klass) {
        return klass.getMeta().java_lang_String.array().equals(klass);
    }

    @SuppressWarnings("unused")
    @Specialization(guards = {"!isStaticObject(value)", "interop.isNull(value)", "!klass.isPrimitive()"})
    Object doForeignNull(Object value, Klass klass,
                    @CachedLibrary(limit = "LIMIT") InteropLibrary interop) {
        return StaticObject.createForeignNull(value);
    }

    // TODO(goltsova): remove this when array bytecodes support foreign arrays
    @SuppressWarnings("unused")
    @Specialization(guards = {"!isStaticObject(value)", "!interop.isNull(value)", "isStringArray(klass)"})
    Object doArray(Object value,
                    ArrayKlass klass,
                    @Cached ToEspressoNode toEspressoNode,
                    @CachedLibrary(limit = "LIMIT") InteropLibrary interop,
                    @Cached BranchProfile exceptionProfile)
                    throws UnsupportedTypeException {
        int length = 0;
        try {
            length = (int) interop.getArraySize(value);
        } catch (UnsupportedMessageException e) {
            exceptionProfile.enter();
            throw UnsupportedTypeException.create(new Object[]{value}, "Casting a non-array foreign object to an array");
        }
        final Klass jlString = klass.getComponentType();
        return jlString.allocateReferenceArray(length, new IntFunction<StaticObject>() {
            @Override
            public StaticObject apply(int index) {
                if (interop.isArrayElementReadable(value, index)) {
                    try {
                        Object elem = interop.readArrayElement(value, index);
                        return (StaticObject) toEspressoNode.execute(elem, jlString);
                    } catch (UnsupportedMessageException | InvalidArrayIndexException e) {
                        rethrow(UnsupportedTypeException.create(new Object[]{value}, klass.getTypeAsString()));
                    } catch (UnsupportedTypeException e) {
                        rethrow(e);
                    }
                }
                rethrow(UnsupportedTypeException.create(new Object[]{value}, klass.getTypeAsString()));
                throw EspressoError.shouldNotReachHere();
            }
        });
    }

    @Specialization(guards = {"!isStaticObject(value)", "!interop.isNull(value)", "isString(klass)"})
    Object doString(Object value,
                    ObjectKlass klass,
                    @CachedLibrary(limit = "LIMIT") InteropLibrary interop,
                    @Cached BranchProfile exceptionProfile)
                    throws UnsupportedTypeException {
        if (interop.isString(value)) {
            try {
                return klass.getMeta().toGuestString(interop.asString(value));
            } catch (UnsupportedMessageException e) {
                exceptionProfile.enter();
                throw EspressoError.shouldNotReachHere("Contract violation: if isString returns true, asString must succeed.");
            }
        }
        exceptionProfile.enter();
        throw UnsupportedTypeException.create(new Object[]{value}, klass.getTypeAsString());
    }

    @Specialization(guards = {"!isStaticObject(value)", "!interop.isNull(value)", "!isString(klass)", "!klass.isAbstract()"})
    Object doForeignClass(Object value, ObjectKlass klass,
                    @CachedLibrary(limit = "LIMIT") InteropLibrary interop,
                    @CachedContext(EspressoLanguage.class) EspressoContext context) throws UnsupportedTypeException {
        try {
            checkHasAllFieldsOrThrow(value, klass, interop, context.getMeta());
        } catch (ClassCastException e) {
            throw UnsupportedTypeException.create(new Object[]{value}, "Could not cast foreign object to " + klass.getNameAsString() + ": " + e.getMessage());
        }
        return StaticObject.createForeign(klass, value, interop);
    }

/*
 * TODO(goltsova): split this into abstract classes and interfaces once casting to interfaces is
 * supported
 */
    @Specialization(guards = {"!isStaticObject(value)", "!interop.isNull(value)", "klass.isAbstract() || klass.isInterface()"})
    Object doForeignAbstract(Object value, ObjectKlass klass,
                    @SuppressWarnings("unused") @CachedLibrary(limit = "LIMIT") InteropLibrary interop) throws UnsupportedTypeException {
        throw UnsupportedTypeException.create(new Object[]{value}, klass.getTypeAsString());
    }

    // TODO(goltsova): remove !isStringArray(klass) once array bytecodes support foreign arrays
    @Specialization(guards = {"!isStaticObject(value)", "!interop.isNull(value)", "!isStringArray(klass)"})
    Object doForeignArray(Object value, ArrayKlass klass,
                    @SuppressWarnings("unused") @CachedLibrary(limit = "LIMIT") InteropLibrary interop) throws UnsupportedTypeException {
        /*
         * Check that the component of the (possibly multi-dimensional) array is either of a
         * primitive type or of a concrete class
         */
        Klass componentKlass = klass.getComponentType();
        while (componentKlass.isArray()) {
            componentKlass = ((ArrayKlass) componentKlass).getComponentType();
        }
        if (!componentKlass.isPrimitive() && !componentKlass.isConcrete()) {
            throw UnsupportedTypeException.create(new Object[]{value}, "Casting to an array with elements of an abstract type is not allowed");
        }

        if (!interop.hasArrayElements(value)) {
            throw UnsupportedTypeException.create(new Object[]{value}, "Cannot cast a non-array value to an array type");
        }
        return StaticObject.createForeign(klass, value, interop);
    }

    public static void checkHasAllFieldsOrThrow(Object value, ObjectKlass klass, InteropLibrary interopLibrary, Meta meta) {
        for (Field f : klass.getDeclaredFields()) {
            if (!f.isStatic() && !interopLibrary.isMemberExisting(value, f.getNameAsString())) {
                throw new ClassCastException("Missing field: " + f.getNameAsString());
            }
        }
        if (klass.getSuperClass() != null) {
            checkHasAllFieldsOrThrow(value, klass.getSuperKlass(), interopLibrary, meta);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable e) throws T {
        throw (T) e;
    }
}
