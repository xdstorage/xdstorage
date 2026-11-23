package io.github.xdstorage.annotations;

import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.idgeneration.XdStorageDummyIdGenerator;
import io.github.xdstorage.idgeneration.XdStorageIdGeneratorType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Per-field storage properties: the declared field length and — on the id field — who
 * generates identifiers.
 *
 * <p>On the {@code @XdStorageObjectId} field, {@code idGeneratorType} selects the
 * generator ({@code DATABASE_GENERATOR} = engine Hi-Lo counter, persisted per class;
 * {@code UUID_GENERATOR} = random UUID, String id fields only; {@code CUSTOM_GENERATOR} =
 * call {@link #idGeneratorClass()}). On any field, {@code length()} is the declared
 * length (default 255) recorded in the class structure.
 */
@Target(value = ElementType.FIELD)
@Retention(value = RetentionPolicy.RUNTIME)
public @interface XdStorageObjectFieldProperties {

/** The declared field length.
     * @return declared length of the field (default 255), recorded in the class structure */
    long length() default 255;

/** Selects the id generator.
     * @return who generates the id (engine Hi-Lo counter by default; UUID or a custom class) */
    XdStorageIdGeneratorType idGeneratorType() default XdStorageIdGeneratorType.DATABASE_GENERATOR;

/** Names the custom generator class.
      * @return the custom {@link IXdStorageIdGenerator} for {@code CUSTOM_GENERATOR} (needs a no-arg or
      * services-locator constructor) */
    Class<? extends IXdStorageIdGenerator> idGeneratorClass() default XdStorageDummyIdGenerator.class;

}
