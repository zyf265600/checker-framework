package org.checkerframework.common.initializedfields;

import com.sun.source.tree.VariableTree;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.signature.qual.BinaryName;
import org.checkerframework.common.accumulation.AccumulationAnalysis;
import org.checkerframework.common.accumulation.AccumulationAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.common.initializedfields.qual.EnsuresInitializedFields;
import org.checkerframework.common.initializedfields.qual.InitializedFields;
import org.checkerframework.common.initializedfields.qual.InitializedFieldsBottom;
import org.checkerframework.framework.source.SourceChecker;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.GenericAnnotatedTypeFactory;
import org.checkerframework.framework.util.Contract;
import org.checkerframework.framework.util.DefaultContractsFromMethod;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.ElementUtils;
import org.checkerframework.javacutil.UserError;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/** The annotated type factory for the Initialized Fields Checker. */
public class InitializedFieldsAnnotatedTypeFactory extends AccumulationAnnotatedTypeFactory {

    /**
     * The type factories that determine whether the default value is consistent with the annotated
     * type. If empty, warn about all uninitialized fields.
     */
    private final List<GenericAnnotatedTypeFactory<?, ?, ?, ?>> defaultValueAtypeFactories;

    /**
     * Creates a new InitializedFieldsAnnotatedTypeFactory.
     *
     * @param checker the checker
     */
    @SuppressWarnings("this-escape")
    public InitializedFieldsAnnotatedTypeFactory(BaseTypeChecker checker) {
        super(checker, InitializedFields.class, InitializedFieldsBottom.class);

        List<String> checkerNames = getCheckerNames();

        // There are usually few subcheckers.
        defaultValueAtypeFactories = new ArrayList<>(2);
        for (String checkerName : checkerNames) {
            if (checkerName.equals(InitializedFieldsChecker.class.getCanonicalName())) {
                continue;
            }
            @SuppressWarnings("signature:argument.type.incompatible") // -processor is a binary name
            GenericAnnotatedTypeFactory<?, ?, ?, ?> atf =
                    createTypeFactoryForProcessor(checkerName);
            if (atf != null) {
                // Add all the subcheckers so that default values are checked for the subcheckers.
                for (SourceChecker subchecker : atf.getChecker().getSubcheckers()) {
                    if (subchecker instanceof BaseTypeChecker) {
                        defaultValueAtypeFactories.add(
                                ((BaseTypeChecker) subchecker).getTypeFactory());
                    }
                }
                defaultValueAtypeFactories.add(atf);
            }
        }

        this.postInit();
    }

    /**
     * Creates a new type factory for the given annotation processor, if it is a type-checker. This
     * does NOT return an existing type factory.
     *
     * @param processorName the fully-qualified class name of an annotation processor
     * @return the type factory for the given annotation processor, or null if it's not a checker
     */
    private @Nullable GenericAnnotatedTypeFactory<?, ?, ?, ?> createTypeFactoryForProcessor(
            @BinaryName String processorName) {
        try {
            Class<?> checkerClass = Class.forName(processorName);
            if (!BaseTypeChecker.class.isAssignableFrom(checkerClass)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            BaseTypeChecker c =
                    ((Class<? extends BaseTypeChecker>) checkerClass)
                            .getDeclaredConstructor()
                            .newInstance();
            c.init(processingEnv);
            c.initChecker();
            BaseTypeVisitor<?> v = c.createSourceVisitorPublic();
            GenericAnnotatedTypeFactory<?, ?, ?, ?> atf = v.createTypeFactoryPublic();
            if (atf == null) {
                throw new UserError(
                        "Cannot find %s; check the classpath or processorpath", processorName);
            }
            return atf;
        } catch (ClassNotFoundException
                | InstantiationException
                | InvocationTargetException
                | IllegalAccessException
                | NoSuchMethodException e) {
            throw new UserError("Problem instantiating " + processorName, e);
        }
    }

    @Override
    public InitializedFieldsContractsFromMethod getContractsFromMethod() {
        return new InitializedFieldsContractsFromMethod(this);
    }

    /** An array consisting only of the string "this". */
    private static final String[] thisStringArray = new String[] {"this"};

    /**
     * A subclass of ContractsFromMethod that adds a postcondition contract to each constructor,
     * requiring that it initializes all fields.
     */
    private class InitializedFieldsContractsFromMethod extends DefaultContractsFromMethod {
        /**
         * Creates an InitializedFieldsContractsFromMethod for the given factory.
         *
         * @param factory the type factory associated with the newly-created ContractsFromMethod
         */
        public InitializedFieldsContractsFromMethod(
                GenericAnnotatedTypeFactory<?, ?, ?, ?> factory) {
            super(factory);
        }

        @Override
        public Set<Contract.Postcondition> getPostconditions(ExecutableElement executableElement) {
            Set<Contract.Postcondition> result = super.getPostconditions(executableElement);

            // Only process constructors defined in source code being type-checked.
            if (declarationFromElement(executableElement) != null
                    && executableElement.getKind() == ElementKind.CONSTRUCTOR) {
                String[] fieldsToInitialize =
                        fieldsToInitialize((TypeElement) executableElement.getEnclosingElement());
                if (fieldsToInitialize.length != 0) {
                    AnnotationMirror initializedFieldsAnno;
                    {
                        AnnotationBuilder builder =
                                new AnnotationBuilder(processingEnv, InitializedFields.class);
                        builder.setValue("value", fieldsToInitialize);
                        initializedFieldsAnno = builder.build();
                    }
                    AnnotationMirror ensuresAnno;
                    {
                        AnnotationBuilder builder =
                                new AnnotationBuilder(
                                        processingEnv, EnsuresInitializedFields.class);
                        builder.setValue("value", thisStringArray);
                        builder.setValue("fields", fieldsToInitialize);
                        ensuresAnno = builder.build();
                    }
                    Contract.Postcondition ensuresContract =
                            new Contract.Postcondition("this", initializedFieldsAnno, ensuresAnno);
                    result.add(ensuresContract);
                }
            }

            return result;
        }
    }

    /**
     * Returns the fields that the constructor must initialize. These are the fields F declared in
     * this class that satisfy all of the following conditions:
     *
     * <ul>
     *   <li>F is a non-final field (if final, Java will issue a warning, so we don't need to).
     *   <li>F's declaration has no initializer.
     *   <li>No initialization block or static initialization block sets the field. (This is handled
     *       automatically because dataflow visits (static) initialization blocks as part of the
     *       constructor.)
     *   <li>F's annotated type is not consistent with the default value (0, 0.0, false, or null)
     * </ul>
     *
     * @param type the type whose fields to list
     * @return the fields whose type is not consistent with the default value, so the constructor
     *     must initialize them
     */
    // It is a bit wasteful that this is recomputed for each constructor.
    private String[] fieldsToInitialize(TypeElement type) {
        List<String> result = new ArrayList<String>();

        for (Element member : type.getEnclosedElements()) {

            if (member.getKind() != ElementKind.FIELD) {
                continue;
            }

            VariableElement field = (VariableElement) member;
            if (ElementUtils.isFinal(field)) {
                continue;
            }

            VariableTree fieldTree = (VariableTree) declarationFromElement(field);
            if (fieldTree.getInitializer() != null) {
                continue;
            }

            if (!defaultValueIsOK(field)) {
                result.add(field.getSimpleName().toString());
            }
        }

        return result.toArray(new String[0]);
    }

    /**
     * Returns true if the default field value (0, 0.0, false, or null) is consistent with the
     * field's declared type.
     *
     * @param field a field
     * @return true if the default field value is consistent with the field's declared type
     */
    private boolean defaultValueIsOK(VariableElement field) {
        if (defaultValueAtypeFactories.isEmpty()) {
            return false;
        }

        for (GenericAnnotatedTypeFactory<?, ?, ?, ?> defaultValueAtypeFactory :
                defaultValueAtypeFactories) {
            // Set the root for all type factories before asking any factory for the type.
            defaultValueAtypeFactory.setRoot(this.getRoot());
        }
        for (GenericAnnotatedTypeFactory<?, ?, ?, ?> defaultValueAtypeFactory :
                defaultValueAtypeFactories) {
            AnnotatedTypeMirror fieldType = defaultValueAtypeFactory.getAnnotatedType(field);
            AnnotatedTypeMirror defaultValueType =
                    defaultValueAtypeFactory.getDefaultValueAnnotatedType(
                            fieldType.getUnderlyingType());
            if (!defaultValueAtypeFactory
                    .getTypeHierarchy()
                    .isSubtype(defaultValueType, fieldType)) {
                return false;
            }
        }

        return true;
    }

    // Overridden because there is no InitalizedFieldsAnalysis.
    @Override
    protected AccumulationAnalysis createFlowAnalysis() {
        return new AccumulationAnalysis(this.getChecker(), this);
    }
}
