/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.espresso.runtime;

import java.util.HashMap;
import java.util.Map;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.meta.JavaKind;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.meta.MetaUtil;

import sun.misc.Unsafe;

public class StaticObjectImpl extends StaticObject {

    private static final Unsafe U;

    static {
        try {
            java.lang.reflect.Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (Unsafe) f.get(null);

        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw EspressoError.shouldNotReachHere(e);
        }
    }

    private Map<String, Object> hiddenFields;

    private final Object[] fields;
    private final int[] wordFields;

    public StaticObjectImpl(ObjectKlass klass, Map<String, Object> hiddenFields, Object[] fields, int[] wordFields) {
        super(klass);
        this.hiddenFields = hiddenFields;
        this.fields = fields;
        this.wordFields = wordFields;
    }

    // FIXME(peterssen): Klass does not need to be initialized, just prepared?.
    public boolean isStatic() {
        return this == getKlass().getStatics();
    }

    // Shallow copy.
    public StaticObject copy() {
        HashMap<String, Object> hiddenFieldsCopy = hiddenFields != null ? new HashMap<>(hiddenFields) : null;
        return new StaticObjectImpl((ObjectKlass) getKlass(), hiddenFieldsCopy, fields.clone(), wordFields.clone());
    }

    public StaticObjectImpl(ObjectKlass klass) {
        this(klass, false);
    }

    public StaticObjectImpl(ObjectKlass klass, boolean isStatic) {
        super(klass);
        // assert !isStatic || klass.isInitialized();
        this.hiddenFields = null;
        this.fields = isStatic ? new Object[klass.getStaticObjectFieldsCount()] : new Object[klass.getObjectFieldsCount()];
        this.wordFields = isStatic ? new int[klass.getStaticWordFieldsCount()] : new int[klass.getWordFieldsCount()];
        initFields(klass, isStatic);
    }

    @ExplodeLoop
    private void initFields(ObjectKlass klass, boolean isStatic) {
        CompilerAsserts.partialEvaluationConstant(klass);
        if (isStatic) {
            for (Field f : klass.getDeclaredFields()) {
                if (f.isStatic()) {
                    if (f.getKind() == JavaKind.Boolean) {
                        wordFields[f.getFieldIndex()] = ((boolean)MetaUtil.defaultFieldValue(f.getKind())) ? 1 : 0;
                    } else if (f.getKind().isSubWord()) {
                        wordFields[f.getFieldIndex()] = MetaUtil.defaultWordFieldValue(f.getKind());
                    } else {
                        fields[f.getFieldIndex()] = MetaUtil.defaultFieldValue(f.getKind());
                    }
                }
            }
        } else {
            // TODO(garcia) Go through fieldTable instead
            for (ObjectKlass curKlass = klass; curKlass != null; curKlass = curKlass.getSuperKlass()) {
                for (Field f : curKlass.getDeclaredFields()) {
                    if (!f.isStatic()) {
                        if (f.getKind() == JavaKind.Boolean) {
                            wordFields[f.getFieldIndex()] = ((boolean)MetaUtil.defaultFieldValue(f.getKind())) ? 1 : 0;
                        } else if (f.getKind().isSubWord()) {
                            wordFields[f.getFieldIndex()] = MetaUtil.defaultWordFieldValue(f.getKind());
                        } else {
                            fields[f.getFieldIndex()] = MetaUtil.defaultFieldValue(f.getKind());
                        }
                    }
                }
            }
        }
    }

    public final Object getFieldVolatile(Field field) {
        assert field.getDeclaringKlass().isAssignableFrom(getKlass());
        return U.getObjectVolatile(fields, Unsafe.ARRAY_OBJECT_BASE_OFFSET + Unsafe.ARRAY_OBJECT_INDEX_SCALE * field.getFieldIndex());
    }

    public final Object getField(Field field) {
        assert field.getDeclaringKlass().isAssignableFrom(getKlass());
        assert !field.getKind().isSubWord();
        Object result;
        if (field.isVolatile()) {
            result = getFieldVolatile(field);
        } else {
            result = fields[field.getFieldIndex()];
        }
        assert result != null;
        return result;
    }

    public final int getWordField(Field field) {
        assert field.getDeclaringKlass().isAssignableFrom(getKlass());
        assert field.getKind().isSubWord();
        int result;
        if (field.isVolatile()) {
            result = getWordFieldVolatile(field);
        } else {
            result = wordFields[field.getFieldIndex()];
        }
        return result;
    }

    public final int getWordFieldVolatile(Field field) {
        assert field.getDeclaringKlass().isAssignableFrom(getKlass());
        return U.getIntVolatile(wordFields, Unsafe.ARRAY_OBJECT_BASE_OFFSET + Unsafe.ARRAY_OBJECT_INDEX_SCALE * field.getFieldIndex());
    }

    public final void setFieldVolatile(Field field, Object value) {
        assert field.getDeclaringKlass().isAssignableFrom(getKlass());
        U.putObjectVolatile(fields, Unsafe.ARRAY_OBJECT_BASE_OFFSET + Unsafe.ARRAY_OBJECT_INDEX_SCALE * field.getFieldIndex(), value);
    }

    public final void setField(Field field, Object value) {
        assert field.getDeclaringKlass().isAssignableFrom(getKlass());
        assert !field.getKind().isSubWord();
        if (field.isVolatile()) {
            setFieldVolatile(field, value);
        } else {
            fields[field.getFieldIndex()] = value;
        }
    }

    public final void setWordFieldVolatile(Field field, int value) {
        assert field.getDeclaringKlass().isAssignableFrom(getKlass());
        U.putIntVolatile(wordFields, Unsafe.ARRAY_OBJECT_BASE_OFFSET + Unsafe.ARRAY_OBJECT_INDEX_SCALE * field.getFieldIndex(), value);
    }

    public final void setWordField(Field field, int value) {
        assert field.getDeclaringKlass().isAssignableFrom(getKlass());
        assert field.getKind().isSubWord();
        if (field.isVolatile()) {
            setWordFieldVolatile(field, value);
        } else {
            wordFields[field.getFieldIndex()] = value;
        }
    }

    @TruffleBoundary
    @Override
    public String toString() {
        if (getKlass() == getKlass().getMeta().String) {
            return Meta.toHostString(this);
        }
        return getKlass().getType().toString();
    }

    @TruffleBoundary
    public void setHiddenField(String name, Object value) {
        if (hiddenFields == null) {
            hiddenFields = new HashMap<>();
        }
        hiddenFields.putIfAbsent(name, value);
    }

    @TruffleBoundary
    public Object getHiddenField(String name) {
        if (hiddenFields == null) {
            return null;
        }
        return hiddenFields.get(name);
    }

    @Override
    public ForeignAccess getForeignAccess() {
        return StaticObjectMessageResolutionForeign.ACCESS;
    }
}
