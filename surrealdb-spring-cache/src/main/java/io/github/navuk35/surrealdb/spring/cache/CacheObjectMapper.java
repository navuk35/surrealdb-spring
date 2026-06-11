package io.github.navuk35.surrealdb.spring.cache;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.TypeIdResolver;
import com.fasterxml.jackson.databind.jsontype.impl.ClassNameIdResolver;
import com.fasterxml.jackson.databind.type.TypeFactory;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the mapper used for cache payloads: registered modules (java.time
 * etc.), ISO-8601 dates, and {@code @class} type information so values
 * round-trip as their original types. Hidden JDK collection classes
 * ({@code List.of()} → {@code ImmutableCollections$List12},
 * {@code Arrays.asList()}, {@code Collections.unmodifiableList()}) cannot be
 * instantiated by Jackson on read, so their type ids are rewritten to
 * portable equivalents at write time.
 */
final class CacheObjectMapper {

    private CacheObjectMapper() {
    }

    static ObjectMapper create(ObjectMapper base) {
        ObjectMapper mapper = base.copy();
        mapper.findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        PolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .build();
        mapper.setDefaultTyping(new PortableCollectionsTypeResolverBuilder(validator)
                .init(JsonTypeInfo.Id.CLASS, null)
                .inclusion(JsonTypeInfo.As.PROPERTY));
        return mapper;
    }

    private static final class PortableCollectionsTypeResolverBuilder
            extends ObjectMapper.DefaultTypeResolverBuilder {

        PortableCollectionsTypeResolverBuilder(PolymorphicTypeValidator validator) {
            super(ObjectMapper.DefaultTyping.NON_FINAL, validator);
        }

        /**
         * NON_FINAL skips final classes — but {@code List.of()} et al. are
         * final implementation classes, leaving their payloads without the
         * type wrapper the deserializer requires for arrays. Collections and
         * maps therefore always carry type info (rewritten to portable
         * classes by the id resolver below).
         */
        @Override
        public boolean useForType(JavaType t) {
            Class<?> raw = t.getRawClass();
            if (Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw)) {
                return true;
            }
            return super.useForType(t);
        }

        @Override
        protected TypeIdResolver idResolver(MapperConfig<?> config, JavaType baseType,
                PolymorphicTypeValidator subtypeValidator, Collection<NamedType> subtypes,
                boolean forSer, boolean forDeser) {
            return new PortableCollectionsIdResolver(baseType, config.getTypeFactory(),
                    subtypeValidator);
        }
    }

    private static final class PortableCollectionsIdResolver extends ClassNameIdResolver {

        PortableCollectionsIdResolver(JavaType baseType, TypeFactory typeFactory,
                PolymorphicTypeValidator validator) {
            super(baseType, typeFactory, validator);
        }

        @Override
        public String idFromValue(Object value) {
            return idFromValueAndType(value, value.getClass());
        }

        @Override
        public String idFromValueAndType(Object value, Class<?> type) {
            Class<?> portable = portableEquivalent(value, type);
            if (portable != null) {
                return portable.getName();
            }
            return super.idFromValueAndType(value, type);
        }

        private static Class<?> portableEquivalent(Object value, Class<?> type) {
            if (type == null || isInstantiable(type)) {
                return null;
            }
            if (value instanceof List) {
                return ArrayList.class;
            }
            if (value instanceof Set) {
                return LinkedHashSet.class;
            }
            if (value instanceof Map) {
                return LinkedHashMap.class;
            }
            return null;
        }

        private static boolean isInstantiable(Class<?> type) {
            if (!Modifier.isPublic(type.getModifiers())) {
                return false;
            }
            String name = type.getName();
            return !name.startsWith("java.util.ImmutableCollections")
                    && !name.startsWith("java.util.Collections$")
                    && !name.startsWith("java.util.Arrays$");
        }
    }
}
