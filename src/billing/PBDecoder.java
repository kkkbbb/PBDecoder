package billing;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;

import org.apache.commons.text.StringEscapeUtils;

import static billing.PBDecoder.FieldType.objs;
import static javax.swing.JColorChooser.showDialog;
//import org.apache.commons.lang3.StringEscapeUtils;

class PBDecoder {
    private static final int INTS_PER_FIELD = 3;
    private static final int OFFSET_BITS = 20;
    private static final int OFFSET_MASK = 0XFFFFF;
    private static final int FIELD_TYPE_MASK = 0x0FF00000;
    private static final int REQUIRED_MASK = 0x10000000;
    private static final int ENFORCE_UTF8_MASK = 0x20000000;
    private static final int NO_PRESENCE_SENTINEL = -1 & OFFSET_MASK;
    private static final int[] EMPTY_INT_ARRAY = new int[0];

    /**
     * An offset applied to the field type ID for scalar fields that are a member of
     * a oneof.
     */
    static final int ONEOF_TYPE_OFFSET = 51 /* FieldType.MAP + 1 */;

    public enum JavaType {
        VOID(Void.class, Void.class, null),
        INT(int.class, Integer.class, 0),
        LONG(long.class, Long.class, 0L),
        FLOAT(float.class, Float.class, 0F),
        DOUBLE(double.class, Double.class, 0D),
        BOOLEAN(boolean.class, Boolean.class, false),
        STRING(String.class, String.class, ""),
        BYTE_STRING(byte[].class, byte[].class, new byte[] {}),
        ENUM(int.class, Integer.class, null),
        MESSAGE(Object.class, Object.class, null);

        private final Class<?> type;
        private final Class<?> boxedType;
        private final Object defaultDefault;

        JavaType(Class<?> type, Class<?> boxedType, Object defaultDefault) {
            this.type = type;
            this.boxedType = boxedType;
            this.defaultDefault = defaultDefault;
        }

        /**
         * The default default value for fields of this type, if it's a primitive type.
         */
        public Object getDefaultDefault() {
            return defaultDefault;
        }

        /**
         * Gets the required type for a field that would hold a value of this type.
         */
        public Class<?> getType() {
            return type;
        }

        /**
         * @return the boxedType
         */
        public Class<?> getBoxedType() {
            return boxedType;
        }

        /**
         * Indicates whether or not this {@link JavaType} can be applied to a field of
         * the given type.
         */
        public boolean isValidType(Class<?> t) {
            return type.isAssignableFrom(t);
        }
    }

    public enum FieldType {
        DOUBLE(0, Collection.SCALAR, JavaType.DOUBLE),
        FLOAT(1, Collection.SCALAR, JavaType.FLOAT),
        INT64(2, Collection.SCALAR, JavaType.LONG),
        UINT64(3, Collection.SCALAR, JavaType.LONG),
        INT32(4, Collection.SCALAR, JavaType.INT),
        FIXED64(5, Collection.SCALAR, JavaType.LONG),
        FIXED32(6, Collection.SCALAR, JavaType.INT),
        BOOL(7, Collection.SCALAR, JavaType.BOOLEAN),
        STRING(8, Collection.SCALAR, JavaType.STRING),
        MESSAGE(9, Collection.SCALAR, JavaType.MESSAGE),
        BYTES(10, Collection.SCALAR, JavaType.BYTE_STRING),
        UINT32(11, Collection.SCALAR, JavaType.INT),
        ENUM(12, Collection.SCALAR, JavaType.ENUM),
        SFIXED32(13, Collection.SCALAR, JavaType.INT),
        SFIXED64(14, Collection.SCALAR, JavaType.LONG),
        SINT32(15, Collection.SCALAR, JavaType.INT),
        SINT64(16, Collection.SCALAR, JavaType.LONG),
        GROUP(17, Collection.SCALAR, JavaType.MESSAGE),
        DOUBLE_LIST(18, Collection.VECTOR, JavaType.DOUBLE),
        FLOAT_LIST(19, Collection.VECTOR, JavaType.FLOAT),
        INT64_LIST(20, Collection.VECTOR, JavaType.LONG),
        UINT64_LIST(21, Collection.VECTOR, JavaType.LONG),
        INT32_LIST(22, Collection.VECTOR, JavaType.INT),
        FIXED64_LIST(23, Collection.VECTOR, JavaType.LONG),
        FIXED32_LIST(24, Collection.VECTOR, JavaType.INT),
        BOOL_LIST(25, Collection.VECTOR, JavaType.BOOLEAN),
        STRING_LIST(26, Collection.VECTOR, JavaType.STRING),
        MESSAGE_LIST(27, Collection.VECTOR, JavaType.MESSAGE),
        BYTES_LIST(28, Collection.VECTOR, JavaType.BYTE_STRING),
        UINT32_LIST(29, Collection.VECTOR, JavaType.INT),
        ENUM_LIST(30, Collection.VECTOR, JavaType.ENUM),
        SFIXED32_LIST(31, Collection.VECTOR, JavaType.INT),
        SFIXED64_LIST(32, Collection.VECTOR, JavaType.LONG),
        SINT32_LIST(33, Collection.VECTOR, JavaType.INT),
        SINT64_LIST(34, Collection.VECTOR, JavaType.LONG),
        DOUBLE_LIST_PACKED(35, Collection.PACKED_VECTOR, JavaType.DOUBLE),
        FLOAT_LIST_PACKED(36, Collection.PACKED_VECTOR, JavaType.FLOAT),
        INT64_LIST_PACKED(37, Collection.PACKED_VECTOR, JavaType.LONG),
        UINT64_LIST_PACKED(38, Collection.PACKED_VECTOR, JavaType.LONG),
        INT32_LIST_PACKED(39, Collection.PACKED_VECTOR, JavaType.INT),
        FIXED64_LIST_PACKED(40, Collection.PACKED_VECTOR, JavaType.LONG),
        FIXED32_LIST_PACKED(41, Collection.PACKED_VECTOR, JavaType.INT),
        BOOL_LIST_PACKED(42, Collection.PACKED_VECTOR, JavaType.BOOLEAN),
        UINT32_LIST_PACKED(43, Collection.PACKED_VECTOR, JavaType.INT),
        ENUM_LIST_PACKED(44, Collection.PACKED_VECTOR, JavaType.ENUM),
        SFIXED32_LIST_PACKED(45, Collection.PACKED_VECTOR, JavaType.INT),
        SFIXED64_LIST_PACKED(46, Collection.PACKED_VECTOR, JavaType.LONG),
        SINT32_LIST_PACKED(47, Collection.PACKED_VECTOR, JavaType.INT),
        SINT64_LIST_PACKED(48, Collection.PACKED_VECTOR, JavaType.LONG),
        GROUP_LIST(49, Collection.VECTOR, JavaType.MESSAGE),
        MAP(50, Collection.MAP, JavaType.VOID);

        private final JavaType javaType;
        private final int id;
        private final Collection collection;
        private final Class<?> elementType;
        private final boolean primitiveScalar;
        static String[] objs;

        FieldType(int id, Collection collection, JavaType javaType) {
            this.id = id;
            this.collection = collection;
            this.javaType = javaType;

            switch (collection) {
                case MAP:
                    elementType = javaType.getBoxedType();
                    break;
                case VECTOR:
                    elementType = javaType.getBoxedType();
                    break;
                case SCALAR:
                default:
                    elementType = null;
                    break;
            }

            boolean primitiveScalar = false;
            if (collection == Collection.SCALAR) {
                switch (javaType) {
                    case BYTE_STRING:
                    case MESSAGE:
                    case STRING:
                        break;
                    default:
                        primitiveScalar = true;
                        break;
                }
            }
            this.primitiveScalar = primitiveScalar;
        }

        /** A reliable unique identifier for this type. */
        public int id() {
            return id;
        }

        /**
         * Gets the {@link JavaType} for this field. For lists, this identifies the type
         * of the elements
         * contained within the list.
         */
        public JavaType getJavaType() {
            return javaType;
        }

        /**
         * Indicates whether a list field should be represented on the wire in packed
         * form.
         */
        public boolean isPacked() {
            return Collection.PACKED_VECTOR.equals(collection);
        }

        /**
         * Indicates whether this field type represents a primitive scalar value. If
         * this is {@code true},
         * then {@link #isScalar()} will also be {@code true}.
         */
        public boolean isPrimitiveScalar() {
            return primitiveScalar;
        }

        /** Indicates whether this field type represents a scalar value. */
        public boolean isScalar() {
            return collection == Collection.SCALAR;
        }

        /** Indicates whether this field represents a list of values. */
        public boolean isList() {
            return collection.isList();
        }

        /** Indicates whether this field represents a map. */
        public boolean isMap() {
            return collection == Collection.MAP;
        }

        /**
         * Indicates whether or not this {@link FieldType} can be applied to the given
         * {@link Field}.
         */
        public boolean isValidForField(Field field) {
            if (Collection.VECTOR.equals(collection)) {
                return isValidForList(field);
            } else {
                return javaType.getType().isAssignableFrom(field.getType());
            }
        }

        private boolean isValidForList(Field field) {
            Class<?> clazz = field.getType();
            if (!javaType.getType().isAssignableFrom(clazz)) {
                // The field isn't a List type.
                return false;
            }
            Type[] types = EMPTY_TYPES;
            Type genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType) {
                types = ((ParameterizedType) field.getGenericType()).getActualTypeArguments();
            }
            Type listParameter = getListParameter(clazz, types);
            if (!(listParameter instanceof Class)) {
                // It's a wildcard, we should allow anything in the list.
                return true;
            }
            return elementType.isAssignableFrom((Class<?>) listParameter);
        }

        /**
         * Looks up the appropriate {@link FieldType} by it's identifier.
         *
         * @return the {@link FieldType} or {@code null} if not found.
         */
        public static FieldType forId(int id) {
            if (id < 0 || id >= VALUES.length) {
                return null;
            }
            return VALUES[id];
        }

        private static final FieldType[] VALUES;
        private static final Type[] EMPTY_TYPES = new Type[0];

        static {
            FieldType[] values = values();
            VALUES = new FieldType[values.length];
            for (FieldType type : values) {
                VALUES[type.id] = type;
            }
        }

        /**
         * Given a class, finds a generic super class or interface that extends
         * {@link List}.
         *
         * @return the generic super class/interface, or {@code null} if not found.
         */
        private static Type getGenericSuperList(Class<?> clazz) {
            // First look at interfaces.
            Type[] genericInterfaces = clazz.getGenericInterfaces();
            for (Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType parameterizedType = (ParameterizedType) genericInterface;
                    Class<?> rawType = (Class<?>) parameterizedType.getRawType();
                    if (List.class.isAssignableFrom(rawType)) {
                        return genericInterface;
                    }
                }
            }

            // Try the subclass
            Type type = clazz.getGenericSuperclass();
            if (type instanceof ParameterizedType) {
                ParameterizedType parameterizedType = (ParameterizedType) type;
                Class<?> rawType = (Class<?>) parameterizedType.getRawType();
                if (List.class.isAssignableFrom(rawType)) {
                    return type;
                }
            }

            // No super class/interface extends List.
            return null;
        }

        /**
         * Inspects the inheritance hierarchy for the given class and finds the generic
         * type parameter for
         * {@link List}.
         *
         * @param clazz     the class to begin the search.
         * @param realTypes the array of actual type parameters for {@code clazz}. These
         *                  will be used to
         *                  substitute generic parameters up the inheritance hierarchy.
         *                  If {@code clazz} does not have
         *                  any generic parameters, this list should be empty.
         * @return the {@link List} parameter.
         */
        private static Type getListParameter(Class<?> clazz, Type[] realTypes) {
            top: while (clazz != List.class) {
                // First look at generic subclass and interfaces.
                Type genericType = getGenericSuperList(clazz);
                if (genericType instanceof ParameterizedType) {
                    // Replace any generic parameters with the real values.
                    ParameterizedType parameterizedType = (ParameterizedType) genericType;
                    Type[] superArgs = parameterizedType.getActualTypeArguments();
                    for (int i = 0; i < superArgs.length; ++i) {
                        Type superArg = superArgs[i];
                        if (superArg instanceof TypeVariable) {
                            // Get the type variables for this class so that we can match them to the
                            // variables
                            // used on the super class.
                            TypeVariable<?>[] clazzParams = clazz.getTypeParameters();
                            if (realTypes.length != clazzParams.length) {
                                throw new RuntimeException("Type array mismatch");
                            }

                            // Replace the variable parameter with the real type.
                            boolean foundReplacement = false;
                            for (int j = 0; j < clazzParams.length; ++j) {
                                if (superArg == clazzParams[j]) {
                                    Type realType = realTypes[j];
                                    superArgs[i] = realType;
                                    foundReplacement = true;
                                    break;
                                }
                            }
                            if (!foundReplacement) {
                                throw new RuntimeException("Unable to find replacement for " + superArg);
                            }
                        }
                    }

                    Class<?> parent = (Class<?>) parameterizedType.getRawType();

                    realTypes = superArgs;
                    clazz = parent;
                    continue;
                }

                // None of the parameterized types inherit List. Just continue up the
                // inheritance hierarchy
                // toward the List interface until we can identify the parameters.
                realTypes = EMPTY_TYPES;
                for (Class<?> iface : clazz.getInterfaces()) {
                    if (List.class.isAssignableFrom(iface)) {
                        clazz = iface;
                        continue top;
                    }
                }
                clazz = clazz.getSuperclass();
            }

            if (realTypes.length != 1) {
                throw new RuntimeException("Unable to identify parameter type for List<T>");
            }
            return realTypes[0];
        }

        enum Collection {
            SCALAR(false),
            VECTOR(true),
            PACKED_VECTOR(true),
            MAP(false);

            private final boolean isList;

            Collection(boolean isList) {
                this.isList = isList;
            }

            /** @return the isList */
            public boolean isList() {
                return isList;
            }
        }
    }

    // 手动解码 Unicode 字符串
    private static String decodeUnicodeString(String unicodeString) {
        StringBuilder sb = new StringBuilder();

        int index = 0;
        while (index < unicodeString.length()) {
            if (unicodeString.charAt(index) == '\\' && index + 1 < unicodeString.length()
                    && unicodeString.charAt(index + 1) == 'u') {
                // 找到\\u转义序列
                if (index + 6 <= unicodeString.length()) {
                    // 截取转义序列
                    String hexCode = unicodeString.substring(index + 2, index + 6);
                    try {
                        // 将转义序列解析为 Unicode 字符并追加到结果字符串
                        int unicodeValue = Integer.parseInt(hexCode, 16);
                        sb.append((char) unicodeValue);
                    } catch (NumberFormatException e) {
                        // 转义序列无效，跳过
                        e.printStackTrace();
                    }
                    index += 6;
                } else {
                    // 转义序列不完整，跳过
                    index += 2;
                }
            } else {
                // 非转义字符，直接追加到结果字符串
                sb.append(unicodeString.charAt(index));
                index++;
            }
        }

        return sb.toString();
    }

    public static String pbTypeToString(int type) {
        try{
            return FieldType.values()[type].name();
        }catch (Exception e){
            return "UnknowType"+type;
        }

    }

    public static String dumpProtoBuffNew(String info, Object[] argObjects) {
        final boolean isProto3 = getSyntax(info) == 3;
        StringBuilder dumpstr = new StringBuilder();

        dumpstr.append("\nisProto3: " + isProto3);
        final int length = info.length();
        int i = 0;

        int next = info.charAt(i++);
        if (next >= 0xD800) {
            int result = next & 0x1FFF;
            int shift = 13;
            while ((next = info.charAt(i++)) >= 0xD800) {
                result |= (next & 0x1FFF) << shift;
                shift += 13;
            }
            next = result | (next << shift);
        }
        final int unusedFlags = next;

        next = info.charAt(i++);
        if (next >= 0xD800) {
            int result = next & 0x1FFF;
            int shift = 13;
            while ((next = info.charAt(i++)) >= 0xD800) {
                result |= (next & 0x1FFF) << shift;
                shift += 13;
            }
            next = result | (next << shift);
        }
        final int fieldCount = next;

        final int oneofCount;
        final int hasBitsCount;
        final int minFieldNumber;
        final int maxFieldNumber;
        final int numEntries;
        final int mapFieldCount;
        final int repeatedFieldCount;
        final int checkInitialized;
        final int[] intArray;
        int objectsPosition;
        if (fieldCount == 0) {
            oneofCount = 0;
            hasBitsCount = 0;
            minFieldNumber = 0;
            maxFieldNumber = 0;
            numEntries = 0;
            mapFieldCount = 0;
            repeatedFieldCount = 0;
            checkInitialized = 0;
            intArray = EMPTY_INT_ARRAY;
            objectsPosition = 0;
        } else {
            next = info.charAt(i++);
            if (next >= 0xD800) {
                int result = next & 0x1FFF;
                int shift = 13;
                while ((next = info.charAt(i++)) >= 0xD800) {
                    result |= (next & 0x1FFF) << shift;
                    shift += 13;
                }
                next = result | (next << shift);
            }
            oneofCount = next;

            next = info.charAt(i++);
            if (next >= 0xD800) {
                int result = next & 0x1FFF;
                int shift = 13;
                while ((next = info.charAt(i++)) >= 0xD800) {
                    result |= (next & 0x1FFF) << shift;
                    shift += 13;
                }
                next = result | (next << shift);
            }
            hasBitsCount = next;

            next = info.charAt(i++);
            if (next >= 0xD800) {
                int result = next & 0x1FFF;
                int shift = 13;
                while ((next = info.charAt(i++)) >= 0xD800) {
                    result |= (next & 0x1FFF) << shift;
                    shift += 13;
                }
                next = result | (next << shift);
            }
            minFieldNumber = next;

            next = info.charAt(i++);
            if (next >= 0xD800) {
                int result = next & 0x1FFF;
                int shift = 13;
                while ((next = info.charAt(i++)) >= 0xD800) {
                    result |= (next & 0x1FFF) << shift;
                    shift += 13;
                }
                next = result | (next << shift);
            }
            maxFieldNumber = next;

            next = info.charAt(i++);
            if (next >= 0xD800) {
                int result = next & 0x1FFF;
                int shift = 13;
                while ((next = info.charAt(i++)) >= 0xD800) {
                    result |= (next & 0x1FFF) << shift;
                    shift += 13;
                }
                next = result | (next << shift);
            }
            numEntries = next;

            next = info.charAt(i++);
            if (next >= 0xD800) {
                int result = next & 0x1FFF;
                int shift = 13;
                while ((next = info.charAt(i++)) >= 0xD800) {
                    result |= (next & 0x1FFF) << shift;
                    shift += 13;
                }
                next = result | (next << shift);
            }
            mapFieldCount = next;

            next = info.charAt(i++);
            if (next >= 0xD800) {
                int result = next & 0x1FFF;
                int shift = 13;
                while ((next = info.charAt(i++)) >= 0xD800) {
                    result |= (next & 0x1FFF) << shift;
                    shift += 13;
                }
                next = result | (next << shift);
            }
            repeatedFieldCount = next;

            next = info.charAt(i++);
            if (next >= 0xD800) {
                int result = next & 0x1FFF;
                int shift = 13;
                while ((next = info.charAt(i++)) >= 0xD800) {
                    result |= (next & 0x1FFF) << shift;
                    shift += 13;
                }
                next = result | (next << shift);
            }
            checkInitialized = next;
            intArray = new int[checkInitialized + mapFieldCount + repeatedFieldCount];
            // Field objects are after a list of (oneof, oneofCase) pairs + a list of
            // hasbits fields.
            objectsPosition = oneofCount * 2 + hasBitsCount;
        }

        final Object[] messageInfoObjects = argObjects;
        int checkInitializedPosition = 0;
        int[] buffer = new int[numEntries * INTS_PER_FIELD];
        Object[] objects = new Object[numEntries * 2];

        int mapFieldIndex = checkInitialized;
        int repeatedFieldIndex = checkInitialized + mapFieldCount;

        int bufferIndex = 0;
        while (i < length) {
            final int fieldNumber;
            final int fieldTypeWithExtraBits;
            final int fieldType;

            next = info.charAt(i++);
            if (next >= 0xD800) {
                int result = next & 0x1FFF;
                int shift = 13;
                while ((next = info.charAt(i++)) >= 0xD800) {
                    result |= (next & 0x1FFF) << shift;
                    shift += 13;
                }
                next = result | (next << shift);
            }
            fieldNumber = next;

            next = info.charAt(i++);
            if (next >= 0xD800) {
                int result = next & 0x1FFF;
                int shift = 13;
                while ((next = info.charAt(i++)) >= 0xD800) {
                    result |= (next & 0x1FFF) << shift;
                    shift += 13;
                }
                next = result | (next << shift);
            }
            fieldTypeWithExtraBits = next;
            fieldType = fieldTypeWithExtraBits & 0xFF;

            if ((fieldTypeWithExtraBits & 0x400) != 0) {
                intArray[checkInitializedPosition++] = bufferIndex;
            }

            final int fieldOffset;
            final int presenceMaskShift;
            final int presenceFieldOffset;

            // Oneof
            if (fieldType >= ONEOF_TYPE_OFFSET) {
                next = info.charAt(i++);
                if (next >= 0xD800) {
                    int result = next & 0x1FFF;
                    int shift = 13;
                    while ((next = info.charAt(i++)) >= 0xD800) {
                        result |= (next & 0x1FFF) << shift;
                        shift += 13;
                    }
                    next = result | (next << shift);
                }
                int oneofIndex = next;

                final int oneofFieldType = fieldType - ONEOF_TYPE_OFFSET;
                if (oneofFieldType == 9 /* FieldType.MESSAGE */
                        || oneofFieldType == 17 /* FieldType.GROUP */) {
                    objects[bufferIndex / INTS_PER_FIELD * 2 + 1] = messageInfoObjects[objectsPosition++];
                } else if (oneofFieldType == 12 /* FieldType.ENUM */) {
                    if (!isProto3) {
                        objects[bufferIndex / INTS_PER_FIELD * 2 + 1] = messageInfoObjects[objectsPosition++];
                    }
                }

                String oneofField = "";
                int index = oneofIndex * 2;
                Object o = messageInfoObjects[index];
                if (o instanceof java.lang.reflect.Field) {
                    // oneofField = (java.lang.reflect.Field) o;
                } else {
                    oneofField = (String) o;
                    // Memoize java.lang.reflect.Field instances for oneof/hasbits fields, since
                    // they're
                    // potentially used for many Protobuf fields. Since there's a 1-1 mapping from
                    // the
                    // Protobuf field to the Java Field for non-oneofs, there's no benefit for
                    // memoizing
                    // those.
                    messageInfoObjects[index] = oneofField;
                }

                fieldOffset = (int) 0;

                String oneofCaseField = "";
                index++;
                o = messageInfoObjects[index];
                if (o instanceof java.lang.reflect.Field) {
                    // oneofCaseField = (java.lang.reflect.Field) o;
                } else {
                    oneofCaseField = (String) o;
                    messageInfoObjects[index] = oneofCaseField;
                }
                dumpstr.append("\n{oneOfField oneofFieldType=" + pbTypeToString(oneofFieldType) + ", oneofField="
                        + oneofField + ", oneofCaseField= " + oneofCaseField + ", fieldNumber=" + fieldNumber + "}");
                presenceFieldOffset = (int) 0;
                presenceMaskShift = 0;
            } else {
                String field = objectsPosition<messageInfoObjects.length? (String) messageInfoObjects[objectsPosition++] : "Unknow";
                if (fieldType == 9 /* FieldType.MESSAGE */ || fieldType == 17 /* FieldType.GROUP */) {
                    objects[bufferIndex / INTS_PER_FIELD * 2 + 1] = field;
                } else if (fieldType == 27 /* FieldType.MESSAGE_LIST */
                        || fieldType == 49 /* FieldType.GROUP_LIST */) {
                    objects[bufferIndex / INTS_PER_FIELD * 2 + 1] = messageInfoObjects[objectsPosition++];
                } else if (fieldType == 12 /* FieldType.ENUM */
                        || fieldType == 30 /* FieldType.ENUM_LIST */
                        || fieldType == 44 /* FieldType.ENUM_LIST_PACKED */) {
                    if (!isProto3) {
                        objects[bufferIndex / INTS_PER_FIELD * 2 + 1] = messageInfoObjects[objectsPosition++];
                    }
                } else if (fieldType == 50 /* FieldType.MAP */) {
                    intArray[mapFieldIndex++] = bufferIndex;
                    objects[bufferIndex / INTS_PER_FIELD * 2] = messageInfoObjects[objectsPosition++];
                    if ((fieldTypeWithExtraBits & 0x800) != 0) {
                        objects[bufferIndex / INTS_PER_FIELD * 2 + 1] = messageInfoObjects[objectsPosition++];
                    }
                }

                fieldOffset = (int) 0;
                String hasBitsField = "";
                boolean hasHasBit = (fieldTypeWithExtraBits & 0x1000) == 0x1000;
                if (hasHasBit && fieldType <= 17 /* FieldType.GROUP */) {
                    next = info.charAt(i++);
                    if (next >= 0xD800) {
                        int result = next & 0x1FFF;
                        int shift = 13;
                        while ((next = info.charAt(i++)) >= 0xD800) {
                            result |= (next & 0x1FFF) << shift;
                            shift += 13;
                        }
                        next = result | (next << shift);
                    }
                    int hasBitsIndex = next;

                    int index = oneofCount * 2 + hasBitsIndex / 32;
                    Object o = messageInfoObjects[index];
                    if (o instanceof java.lang.reflect.Field) {
                        // hasBitsField = (java.lang.reflect.Field) o;
                    } else {
                        hasBitsField = (String) o;
                        messageInfoObjects[index] = hasBitsField;
                    }

                    presenceFieldOffset = (int) 0;
                    presenceMaskShift = hasBitsIndex % 32;
                } else {
                    presenceFieldOffset = NO_PRESENCE_SENTINEL;
                    presenceMaskShift = 0;
                }

                dumpstr.append("\n"+
                        "{Field fieldType=" + pbTypeToString(fieldType) + ", field=" + field + ", hasBitsField="
                                + hasBitsField + ", fieldNumber= " + fieldNumber + "}");
                if (fieldType >= 18 && fieldType <= 49) {
                    // Field types of repeated fields are in a consecutive range from 18
                    // (DOUBLE_LIST) to
                    // 49 (GROUP_LIST).
                    intArray[repeatedFieldIndex++] = fieldOffset;
                }
            }

            buffer[bufferIndex++] = fieldNumber;
            buffer[bufferIndex++] = ((fieldTypeWithExtraBits & 0x200) != 0 ? ENFORCE_UTF8_MASK : 0)
                    | ((fieldTypeWithExtraBits & 0x100) != 0 ? REQUIRED_MASK : 0)
                    | (fieldType << OFFSET_BITS)
                    | fieldOffset;
            buffer[bufferIndex++] = (presenceMaskShift << OFFSET_BITS) | presenceFieldOffset;
        }
        return dumpstr.toString();
    }


    public static int getSyntax(String info) {
        int position = 0;
        int flags;
        int value = (int) info.charAt(position++);
        if (value < 0xD800) {
            flags = value;
        } else {
            int result = value & 0x1FFF;
            int shift = 13;
            while ((value = info.charAt(position++)) >= 0xD800) {
                result |= (value & 0x1FFF) << shift;
                shift += 13;
            }
            flags = result | (value << shift);
        }
        return (flags & 0x1) == 0x1 ? 2 : 3;
    }

    // public static void main(String[] args) {
    // if (args.length < 2) {
    // System.out.println("Usage: java PBDecoder <message info> <message objects>");
    // return;
    // }
    // String strInfo = decodeUnicodeString(args[0]);
    // String[] objects = args[1].split(",");
    // System.out.println("args[1]: " + args[1].toString());
    // dumpProtoBuffNew(strInfo, objects);
    // }
    public static String readFileToString(String filePath) {
        StringBuilder content = new StringBuilder();
        BufferedReader reader = null;

        try {
            reader = new BufferedReader(new FileReader(filePath));
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return content.toString();
    }

    public static void main(String[] args) {
        showDialog();
//        if (args.length < 2) {
//            System.out.println("Usage: java PBDecoder <message info file> <message objects file>");
//            return;
//        }
//        String strInfo = StringEscapeUtils.unescapeJava(args[0]);
//        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
//        String objstr = String.join(" ", subArgs);
        

    }

    private static void showDialog() {
        JFrame frame = new JFrame("MessageInfo还原工具");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(400, 300);

        JTextField textField1 = new JTextField();
        JTextField textField2 = new JTextField();
        JTextArea outputArea = new JTextArea();
        outputArea.setEditable(false);

        JButton button = new JButton("解析");
        button.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String strInfo = textField1.getText().replace("\"","").replace(" ","");
                strInfo = StringEscapeUtils.unescapeJava(strInfo);

                String objstr = textField2.getText();
                objstr = objstr.replace("\"","");
                if(objstr.contains("{")){
                    objstr = objstr.substring(1,objstr.length());
                }
                if (objstr.contains("}")){
                    objstr = objstr.substring(0,objstr.length()-1);
                }
                String[] objects = objstr.split(",");
                String parseStr = dumpProtoBuffNew(strInfo, objects);

                String protoStr;
                try{
                    protoStr = ToProto(UpdateType(parseStr,objects),objstr);
                }catch (Exception ec){
                    protoStr = "发生内部错误";
                }
                outputArea.setText(parseStr+"\n\n"+protoStr);
            }

            private String ToProto(String parseStr,String objstr) {
                String[] s = parseStr.split("\n");
                String[] fieldStr = new String[4];
                StringBuilder protostr = new StringBuilder();
                Map<String,StringBuilder> oneofProtoStr = new HashMap<>();
                StringBuilder outproto = new StringBuilder();
                Map<String,String> linemap = new HashMap<>();
                Map<String,Integer> oneofFieldNum = new HashMap<>();

                for(String line : s){
                    protostr.setLength(0);
                    if(line.contains("oneOfField")) line = line.replace("{oneOfField",""); else line = line.replace("{Field","");
                    line = line.replace("}","");
                    for (int i = 0; i < fieldStr.length; i++) fieldStr[i] = null;

                    if(line.contains("oneofField")) {
                        for(String fields : line.split(",")){
                            String[] field = fields.split("=");
                            if(field.length < 2){
                                continue;
                            }
                            switch (field[0].replace(" ","")){
                                case "oneofFieldType":
                                    fieldStr[0] = field[1];
                                    break;
                                case "oneofField":
                                    fieldStr[1] = field[1];
                                    break;
                                case "oneofCaseField":
                                    fieldStr[2] = field[1];
                                    break;
                                case "fieldNumber":
                                    fieldStr[3] = field[1];
                                    break;
                            }
                        }


                    } else {
                        for(String fields : line.split(",")){
                            String[] field = fields.split("=");
                            if(field.length < 2){
                                continue;
                            }
                            switch (field[0].replace(" ","")){
                                case "fieldType":
                                    fieldStr[0] = field[1];
                                    break;
                                case "field":
                                    fieldStr[1] = field[1];
                                    break;
                                case "hasBitsField":
                                    fieldStr[2] = field[1];
                                    break;
                                case "fieldNumber":
                                    fieldStr[3] = field[1];
                                    break;
                            }
                        }
                    }

                    if(line.contains("oneofField")){
                        if(!oneofProtoStr.containsKey(fieldStr[1])){
                            oneofProtoStr.put(fieldStr[1],new StringBuilder().append("oneof "+fieldStr[1]+" {\n"));
                        }

                        StringBuilder oneofStr = oneofProtoStr.get(fieldStr[1]);
                        Integer oneNum = oneofFieldNum.get(fieldStr[1]);
                        if(oneNum == null){
                            oneofFieldNum.put(fieldStr[1],Integer.valueOf(0));
                            oneNum = 0;
                        }
                        oneofStr.append("\t"+fieldStr[0].toLowerCase() +" oneofField"+ oneNum +" = "+fieldStr[3]+";\n");
                        oneofFieldNum.replace(fieldStr[1],oneofFieldNum.get(fieldStr[1])+1);


                    } else {
                        if(fieldStr[2]!="" && fieldStr[2]!=null){
                            protostr.append("optional ");
                        }else if(fieldStr[0]!=null && fieldStr[0].contains("LIST")){
                            protostr.append("repeated ");
                            fieldStr[0] = fieldStr[0].replace("_LIST","");
                        }else{
                            protostr.append("required ");
                        }
                        if(fieldStr[0]!=null && fieldStr[1]!=null && fieldStr[3]!=null){
                            if (fieldStr[0].contains("PACKED")) protostr.append(fieldStr[0].toLowerCase().replace("_packed","")+" "); else protostr.append(fieldStr[0].toLowerCase()+" ");


                            protostr.append(fieldStr[1]+" =");
                            if(fieldStr[0].contains("PACKED")) protostr.append(fieldStr[3]+" [packed=true];"); else protostr.append(fieldStr[3]+";");



                            linemap.put(fieldStr[1],protostr.toString());
                        }
                    }

                }

                for(StringBuilder oneof : oneofProtoStr.values()){
                    outproto.append(oneof.append("}\n"));
                }

//                int linenumber = 1;
                for (Map.Entry<String, String> entry : linemap.entrySet()) {
                    outproto.append(entry.getValue()+"\n");//+"  //0x"+Integer.toHexString(linenumber)+"\n");
//                    linenumber*=2;
                }

                return outproto.toString();
            }
        });

        JPanel inputPanel = new JPanel(new GridLayout(1, 3, 5, 5));
        inputPanel.add(textField1);
        inputPanel.add(textField2);
        inputPanel.add(button);

        frame.setLayout(new BorderLayout(10, 10));
        frame.add(inputPanel, BorderLayout.NORTH);
        frame.add(new JScrollPane(outputArea), BorderLayout.CENTER);

        // 添加空边框以提供边距
        inputPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        outputArea.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        frame.setVisible(true);
    }

    private static String UpdateType(String parseStr, String[] objects) {
        for (int i = 0; i < objects.length; i++) {
            objects[i] = objects[i].replace(" ","");
        }

        ArrayList<String> obj = new ArrayList<>(Arrays.asList(objects));
        parseStr = parseStr.replace("{oneOfField","").replace("{Field","").replace("}","");
        String[] parsedStr = parseStr.split("\n");
        parsedStr = Arrays.copyOfRange(parsedStr, 2, parsedStr.length);

        //去除oneof和hasbit字段
        for(String parse : parsedStr){
            if(parse.equals("") || !parse.contains(",")){
                continue;
            }
            String tmp1="";
            String tmp2="";
            try{
                String[] field =parse.split(",");
                tmp1 = field[2].split("=")[1].replace(" ","");
                if(field[1].split("=")[0].contains("oneofField")){
                    tmp2 = field[1].split("=")[1].replace(" ","");
                }
            }catch (Exception e){}

            if(obj.contains(tmp1)){
                 obj.remove(tmp1);
            }
            if(obj.contains(tmp2)){
                obj.remove(tmp2);
            }
        }

        int objIndex = 0;
        for(int i=0;i<parsedStr.length;i++){
            String parse = parsedStr[i].replace(" ","");
            String[] fieldStr = parse.split(",");

            String fieldType = fieldStr[0].split("=")[0];
            String MessageType = fieldStr[0].split("=")[1];
            if(fieldType.contains("oneofFieldType")){
                if(MessageType.equals("MESSAGE")){
                    parsedStr[i] = parse.replace("MESSAGE",obj.get(objIndex).replace(".class",""));
                    objIndex+=1;
                    continue;
                }
                if(MessageType.equals("ENUM")){
                    objIndex+=1;
                    continue;
                }
                continue;
            }

            if(MessageType.contains("LIST")){
                if(MessageType.contains("MESSAGE")) {
                    parsedStr[i] = parse.replace("MESSAGE", obj.get(objIndex + 1).replace(".class", ""));
                    objIndex += 2;
                    continue;
                }
//                if(MessageType.contains("ENUM")){
//                    objIndex+=2;
//                    continue;
//                }
            }

            if(MessageType.contains("ENUM")){
                objIndex+=2;
                continue;
            }

            objIndex+=1;
        }

        return String.join("\n",parsedStr);
    }
}