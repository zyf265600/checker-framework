package org.checkerframework.common.basetype;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;

import org.checkerframework.checker.compilermsgs.qual.CompilerMessageKey;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.checkerframework.framework.source.DiagMessage;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedArrayType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedDeclaredType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedIntersectionType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedPrimitiveType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedTypeVariable;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedWildcardType;
import org.checkerframework.framework.type.AnnotatedTypeParameterBounds;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.type.visitor.AnnotatedTypeScanner;
import org.checkerframework.framework.type.visitor.SimpleAnnotatedTypeScanner;
import org.checkerframework.framework.util.AnnotatedTypes;
import org.checkerframework.javacutil.AnnotationMirrorSet;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.BugInCF;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypeAnnotationUtils;
import org.checkerframework.javacutil.TypesUtils;
import org.plumelib.util.ArrayMap;
import org.plumelib.util.IPair;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;

/**
 * A visitor to validate the types in a tree.
 *
 * <p>The validator is called on the type of every expression, such as on the right-hand side of
 * {@code x = Optional.of(Optional.of("baz"));}. However, note that the type of the right-hand side
 * is {@code Optional<? extends Object>}, not {@code Optional<Optional<String>>}.
 *
 * <p>Note: A TypeValidator (this class and its subclasses) cannot tell whether an annotation was
 * written by a programmer or defaulted/inferred/computed by the Checker Framework, because the
 * AnnotatedTypeMirror does not make distinctions about which annotations in an AnnotatedTypeMirror
 * were explicitly written and which were added by a checker. To issue a warning/error only when a
 * programmer writes an annotation, override {@link BaseTypeVisitor#visitAnnotatedType} and {@link
 * BaseTypeVisitor#visitVariable}.
 */
public class BaseTypeValidator extends AnnotatedTypeScanner<Void, Tree> implements TypeValidator {
    /** Is the type valid? This is side-effected by the visitor, and read at the end of visiting. */
    protected boolean isValid = true;

    /** Should the primary annotation on the top level type be checked? */
    protected boolean checkTopLevelDeclaredOrPrimitiveType = true;

    /** BaseTypeChecker. */
    protected final BaseTypeChecker checker;

    /** BaseTypeVisitor. */
    protected final BaseTypeVisitor<?> visitor;

    /** AnnotatedTypeFactory. */
    protected final AnnotatedTypeFactory atypeFactory;

    /** The qualifer hierarchy. */
    protected final QualifierHierarchy qualHierarchy;

    // TODO: clean up coupling between components
    public BaseTypeValidator(
            BaseTypeChecker checker,
            BaseTypeVisitor<?> visitor,
            AnnotatedTypeFactory atypeFactory) {
        this.checker = checker;
        this.visitor = visitor;
        this.atypeFactory = atypeFactory;
        this.qualHierarchy = atypeFactory.getQualifierHierarchy();
    }

    /**
     * Validate the type against the given tree. This method both issues error messages and also
     * returns a boolean value.
     *
     * <p>This is the entry point to the type validator. Neither this method nor visit should be
     * called directly by a visitor, only use {@link BaseTypeVisitor#validateTypeOf(Tree)}.
     *
     * <p>This method is only called on top-level types, but it validates the entire type including
     * components of a compound type. Subclasses should override this only if there is special-case
     * behavior that should be performed only on top-level types.
     *
     * @param type the type to validate
     * @param tree the tree from which the type originated. If the tree is a method tree, {@code
     *     type} is its return type. If the tree is a variable tree, {@code type} is the variable's
     *     type.
     * @return true if the type is valid
     */
    @Override
    public boolean isValid(AnnotatedTypeMirror type, Tree tree) {
        List<DiagMessage> diagMessages = isValidStructurally(type);
        if (!diagMessages.isEmpty()) {
            for (DiagMessage d : diagMessages) {
                checker.report(tree, d);
            }
            return false;
        }
        this.isValid = true;
        this.checkTopLevelDeclaredOrPrimitiveType =
                shouldCheckTopLevelDeclaredOrPrimitiveType(type, tree);
        visit(type, tree);
        return this.isValid;
    }

    /**
     * Should the top-level declared or primitive type be checked?
     *
     * <p>If {@code type} is not a declared or primitive type, then this method returns true.
     *
     * <p>Top-level type is not checked if tree is a local variable or an expression tree.
     *
     * @param type the AnnotatedTypeMirror being validated
     * @param tree a Tree whose type is {@code type}
     * @return whether or not the top-level type should be checked, if {@code type} is a declared or
     *     primitive type.
     */
    protected boolean shouldCheckTopLevelDeclaredOrPrimitiveType(
            AnnotatedTypeMirror type, Tree tree) {
        if (type.getKind() != TypeKind.DECLARED && !type.getKind().isPrimitive()) {
            return true;
        }
        return !TreeUtils.isLocalVariable(tree)
                && (!TreeUtils.isExpressionTree(tree) || TreeUtils.isTypeTree(tree));
    }

    /**
     * Performs some well-formedness checks on the given {@link AnnotatedTypeMirror}. Returns a list
     * of failures. If successful, returns an empty list. The method will never return failures for
     * a valid type, but might not catch all invalid types.
     *
     * <p>This method ensures that the type is structurally or lexically well-formed, but it does
     * not check whether the annotations are semantically sensible. Subclasses should generally
     * override visit methods such as {@link #visitDeclared} rather than this method.
     *
     * <p>Currently, this implementation checks the following (subclasses can extend this behavior):
     *
     * <ol>
     *   <li>There should not be multiple annotations from the same qualifier hierarchy.
     *   <li>There should not be more annotations than the width of the QualifierHierarchy.
     *   <li>If the type is not a type variable, then the number of annotations should be the same
     *       as the width of the QualifierHierarchy.
     *   <li>These properties should also hold recursively for component types of arrays and for
     *       bounds of type variables and wildcards.
     * </ol>
     *
     * This does not test whether the Java type is relevant, because by the time this method is
     * called, the type includes some non-programmer-written annotations.
     *
     * @param type the type to test
     * @return list of reasons the type is invalid, or empty list if the type is valid
     */
    protected List<DiagMessage> isValidStructurally(AnnotatedTypeMirror type) {
        SimpleAnnotatedTypeScanner<List<DiagMessage>, Void> scanner =
                new SimpleAnnotatedTypeScanner<>(
                        (atm, p) -> isTopLevelValidType(atm),
                        DiagMessage::mergeLists,
                        Collections.emptyList());
        return scanner.visit(type, null);
    }

    /**
     * Checks every property listed in {@link #isValidStructurally}, but only for the top level
     * type. If successful, returns an empty list. If not successful, returns diagnostics.
     *
     * @param type the type to be checked
     * @return the diagnostics indicating failure, or an empty list if successful
     */
    // This method returns a singleton or empyty list.  Its return type is List rather than
    // DiagMessage (with null indicting success) because its caller, isValidStructurally(), expects
    // a list.
    protected List<DiagMessage> isTopLevelValidType(AnnotatedTypeMirror type) {
        // multiple annotations from the same hierarchy
        AnnotationMirrorSet annotations = type.getAnnotations();
        AnnotationMirrorSet seenTops = new AnnotationMirrorSet();
        for (AnnotationMirror anno : annotations) {
            AnnotationMirror top = qualHierarchy.getTopAnnotation(anno);
            if (AnnotationUtils.containsSame(seenTops, top)) {
                return Collections.singletonList(
                        DiagMessage.error("type.invalid.conflicting.annos", annotations, type));
            }
            seenTops.add(top);
        }

        boolean canHaveEmptyAnnotationSet = QualifierHierarchy.canHaveEmptyAnnotationSet(type);

        // wrong number of annotations
        if (!canHaveEmptyAnnotationSet && seenTops.size() < qualHierarchy.getWidth()) {
            return Collections.singletonList(
                    DiagMessage.error("type.invalid.too.few.annotations", annotations, type));
        }

        // success
        return Collections.emptyList();
    }

    protected void reportValidityResult(
            @CompilerMessageKey String errorType, AnnotatedTypeMirror type, Tree p) {
        checker.reportError(p, errorType, type.getAnnotations(), type.toString());
        isValid = false;
    }

    /**
     * Like {@link #reportValidityResult}, but the type is printed in the error message without
     * annotations. This method would print "annotation @NonNull is not permitted on type int",
     * whereas {@link #reportValidityResult} would print "annotation @NonNull is not permitted on
     * type @NonNull int". In addition, when the underlying type is a compound type such as
     * {@code @Bad List<String>}, the erased type will be used, i.e., "{@code List}" will print
     * instead of "{@code @Bad List<String>}".
     */
    protected void reportValidityResultOnUnannotatedType(
            @CompilerMessageKey String errorType, AnnotatedTypeMirror type, Tree p) {
        TypeMirror underlying =
                TypeAnnotationUtils.unannotatedType(type.getErased().getUnderlyingType());
        checker.reportError(p, errorType, type.getAnnotations(), underlying.toString());
        isValid = false;
    }

    /**
     * Most errors reported by this class are of the form type.invalid. This method reports when the
     * bounds of a wildcard or type variable don't make sense. Bounds make sense when the effective
     * annotations on the upper bound are supertypes of those on the lower bounds for all
     * hierarchies. To ensure that this subtlety is not lost on users, we report
     * "bound.type.incompatible" and print the bounds along with the invalid type rather than a
     * "type.invalid".
     *
     * @param type the type with invalid bounds
     * @param tree where to report the error
     */
    protected void reportInvalidBounds(AnnotatedTypeMirror type, Tree tree) {
        final String label;
        final AnnotatedTypeMirror upperBound;
        final AnnotatedTypeMirror lowerBound;

        switch (type.getKind()) {
            case TYPEVAR:
                label = "type parameter";
                upperBound = ((AnnotatedTypeVariable) type).getUpperBound();
                lowerBound = ((AnnotatedTypeVariable) type).getLowerBound();
                break;

            case WILDCARD:
                label = "wildcard";
                upperBound = ((AnnotatedWildcardType) type).getExtendsBound();
                lowerBound = ((AnnotatedWildcardType) type).getSuperBound();
                break;

            default:
                throw new BugInCF("Type is not bounded.%ntype=%s%ntree=%s", type, tree);
        }

        checker.reportError(
                tree,
                "bound.type.incompatible",
                label,
                type.toString(),
                upperBound.toString(true),
                lowerBound.toString(true));
        isValid = false;
    }

    protected void reportInvalidType(AnnotatedTypeMirror type, Tree p) {
        reportValidityResult("type.invalid", type, p);
    }

    /**
     * Report an "annotations.on.use" error for the given type and tree.
     *
     * @param type the type with invalid annotations
     * @param p the tree where to report the error
     */
    protected void reportInvalidAnnotationsOnUse(AnnotatedTypeMirror type, Tree p) {
        reportValidityResultOnUnannotatedType("type.invalid.annotations.on.use", type, p);
    }

    @Override
    public Void visitDeclared(AnnotatedDeclaredType type, Tree tree) {
        if (visitedNodes.containsKey(type)) {
            return visitedNodes.get(type);
        }

        boolean skipChecks = checker.shouldSkipUses(type.getUnderlyingType().asElement());

        if (checkTopLevelDeclaredOrPrimitiveType && !skipChecks) {
            // Ensure that type use is a subtype of the element type
            // isValidUse determines the erasure of the types.

            AnnotationMirrorSet bounds =
                    atypeFactory.getTypeDeclarationBounds(type.getUnderlyingType());

            AnnotatedDeclaredType elemType = type.deepCopy();
            elemType.clearAnnotations();
            elemType.addAnnotations(bounds);

            if (!visitor.isValidUse(elemType, type, tree)) {
                reportInvalidAnnotationsOnUse(type, tree);
            }
        }
        // Set checkTopLevelDeclaredType to true, because the next time visitDeclared is called,
        // the type isn't the top level, so always do the check.
        checkTopLevelDeclaredOrPrimitiveType = true;

        if (TreeUtils.isClassTree(tree)) {
            visitedNodes.put(type, null);
            visitClassTypeParameters(type, (ClassTree) tree);
            return null;
        }

        /*
         * Try to reconstruct the ParameterizedTypeTree from the given tree.
         * TODO: there has to be a nicer way to do this...
         */
        IPair<ParameterizedTypeTree, AnnotatedDeclaredType> p =
                extractParameterizedTypeTree(tree, type);
        ParameterizedTypeTree typeArgTree = p.first;
        type = p.second;

        if (typeArgTree == null) {
            return super.visitDeclared(type, tree);
        } // else

        // We put this here because we don't want to put it in visitedNodes before calling
        // super (in the else branch) because that would cause the super implementation
        // to detect that we've already visited type and to immediately return.
        visitedNodes.put(type, null);

        // We have a ParameterizedTypeTree -> visit it.

        visitParameterizedType(type, typeArgTree);

        /*
         * Instead of calling super with the unchanged "tree", adapt the
         * second argument to be the corresponding type argument tree. This
         * ensures that the first and second parameter to this method always
         * correspond. visitDeclared is the only method that had this
         * problem.
         */
        List<? extends AnnotatedTypeMirror> tatypes = type.getTypeArguments();

        if (tatypes == null) {
            return null;
        }

        // May be zero for a "diamond" (inferred type args in constructor invocation).
        int numTypeArgs = typeArgTree.getTypeArguments().size();
        if (numTypeArgs != 0) {
            // TODO: this should be an equality, but in the past it failed with:
            //   daikon/Debug.java; message: size mismatch for type arguments:
            //   @NonNull Object and Class<?>
            // but I didn't manage to reduce it to a test case.
            assert tatypes.size() <= numTypeArgs || skipChecks
                    : "size mismatch for type arguments: " + type + " and " + typeArgTree;

            for (int i = 0; i < tatypes.size(); ++i) {
                scan(tatypes.get(i), typeArgTree.getTypeArguments().get(i));
            }
        }

        // Don't call the super version, because it creates a mismatch
        // between the first and second parameters.
        // return super.visitDeclared(type, tree);

        return null;
    }

    /**
     * Visits the type parameters of a class tree.
     *
     * @param type type of {@code tree}
     * @param tree a class tree
     */
    protected void visitClassTypeParameters(AnnotatedDeclaredType type, ClassTree tree) {
        for (int i = 0, size = type.getTypeArguments().size(); i < size; i++) {
            AnnotatedTypeVariable typeParameter =
                    (AnnotatedTypeVariable) type.getTypeArguments().get(i);
            TypeParameterTree typeParameterTree = tree.getTypeParameters().get(i);
            scan(typeParameter, typeParameterTree);
        }
    }

    /**
     * Visits type parameter bounds.
     *
     * @param typeParameter type of {@code typeParameterTree}
     * @param typeParameterTree a type parameter tree
     */
    protected void visitTypeParameterBounds(
            AnnotatedTypeVariable typeParameter, TypeParameterTree typeParameterTree) {
        List<? extends Tree> boundTrees = typeParameterTree.getBounds();
        if (boundTrees.size() == 1) {
            scan(typeParameter.getUpperBound(), boundTrees.get(0));
        } else if (boundTrees.size() == 0) {
            // The upper bound is implicitly Object
            scan(typeParameter.getUpperBound(), typeParameterTree);
        } else {
            AnnotatedIntersectionType intersectionType =
                    (AnnotatedIntersectionType) typeParameter.getUpperBound();
            for (int j = 0; j < intersectionType.getBounds().size(); j++) {
                scan(intersectionType.getBounds().get(j), boundTrees.get(j));
            }
        }
    }

    /**
     * If {@code tree} has a {@link ParameterizedTypeTree}, then the tree and its type is returned.
     * Otherwise null and {@code type} are returned.
     *
     * @param tree tree to search
     * @param type type to return if no {@code ParameterizedTypeTree} is found
     * @return if {@code tree} has a {@code ParameterizedTypeTree}, then returns the tree and its
     *     type. Otherwise, returns null and {@code type}.
     */
    private IPair<@Nullable ParameterizedTypeTree, AnnotatedDeclaredType>
            extractParameterizedTypeTree(Tree tree, AnnotatedDeclaredType type) {
        ParameterizedTypeTree typeargtree = null;

        switch (tree.getKind()) {
            case VARIABLE:
                Tree lt = ((VariableTree) tree).getType();
                if (lt instanceof ParameterizedTypeTree) {
                    typeargtree = (ParameterizedTypeTree) lt;
                } else {
                    // System.out.println("Found a: " + lt);
                }
                break;
            case PARAMETERIZED_TYPE:
                typeargtree = (ParameterizedTypeTree) tree;
                break;
            case NEW_CLASS:
                NewClassTree nct = (NewClassTree) tree;
                ExpressionTree nctid = nct.getIdentifier();
                if (nctid instanceof ParameterizedTypeTree) {
                    typeargtree = (ParameterizedTypeTree) nctid;
                    /*
                     * This is quite tricky... for anonymous class instantiations,
                     * the type at this point has no type arguments. By doing the
                     * following, we get the type arguments again.
                     */
                    type = (AnnotatedDeclaredType) atypeFactory.getAnnotatedType(typeargtree);
                }
                break;
            case ANNOTATED_TYPE:
                AnnotatedTypeTree tr = (AnnotatedTypeTree) tree;
                ExpressionTree undtr = tr.getUnderlyingType();
                if (undtr instanceof ParameterizedTypeTree) {
                    typeargtree = (ParameterizedTypeTree) undtr;
                } else if (undtr instanceof IdentifierTree) {
                    // @Something D -> Nothing to do
                } else {
                    // TODO: add more test cases to ensure that nested types are
                    // handled correctly,
                    // e.g. @Nullable() List<@Nullable Object>[][]
                    IPair<ParameterizedTypeTree, AnnotatedDeclaredType> p =
                            extractParameterizedTypeTree(undtr, type);
                    typeargtree = p.first;
                    type = p.second;
                }
                break;
            case IDENTIFIER:
            case ARRAY_TYPE:
            case NEW_ARRAY:
            case MEMBER_SELECT:
            case UNBOUNDED_WILDCARD:
            case EXTENDS_WILDCARD:
            case SUPER_WILDCARD:
            case TYPE_PARAMETER:
                // Nothing to do.
                break;
            case METHOD:
                // If a MethodTree is passed, it's just the return type that is validated.
                // See BaseTypeVisitor#validateTypeOf.
                MethodTree methodTree = (MethodTree) tree;
                if (methodTree.getReturnType() instanceof ParameterizedTypeTree) {
                    typeargtree = (ParameterizedTypeTree) methodTree.getReturnType();
                }
                break;
            default:
                // The parameterized type is the result of some expression tree.
                // No need to do anything further.
                break;
        }

        return IPair.of(typeargtree, type);
    }

    @Override
    @SuppressWarnings(
            "signature:argument.type.incompatible") // PrimitiveType.toString(): @PrimitiveType
    public Void visitPrimitive(AnnotatedPrimitiveType type, Tree tree) {
        if (!checkTopLevelDeclaredOrPrimitiveType
                || checker.shouldSkipUses(type.getUnderlyingType().toString())) {
            return super.visitPrimitive(type, tree);
        }

        if (!visitor.isValidUse(type, tree)) {
            reportInvalidAnnotationsOnUse(type, tree);
        }

        return super.visitPrimitive(type, tree);
    }

    @Override
    public Void visitArray(AnnotatedArrayType type, Tree tree) {
        // TODO: is there already or add a helper method
        // to determine the non-array component type
        AnnotatedTypeMirror comp = type;
        do {
            comp = ((AnnotatedArrayType) comp).getComponentType();
        } while (comp.getKind() == TypeKind.ARRAY);

        if (comp.getKind() == TypeKind.DECLARED
                && checker.shouldSkipUses(
                        ((AnnotatedDeclaredType) comp).getUnderlyingType().asElement())) {
            return super.visitArray(type, tree);
        }

        if (!visitor.isValidUse(type, tree)) {
            reportInvalidAnnotationsOnUse(type, tree);
        }

        return super.visitArray(type, tree);
    }

    /**
     * Checks that the annotations on the type arguments supplied to a type or a method invocation
     * are within the bounds of the type variables as declared, and issues the
     * "type.argument.type.incompatible" error if they are not.
     *
     * @param type the type to check
     * @param tree the type's tree
     */
    protected Void visitParameterizedType(AnnotatedDeclaredType type, ParameterizedTypeTree tree) {
        // System.out.printf("TypeValidator.visitParameterizedType: type: %s, tree: %s%n", type,
        // tree);

        if (TreeUtils.isDiamondTree(tree)) {
            return null;
        }

        TypeElement element = (TypeElement) type.getUnderlyingType().asElement();
        if (checker.shouldSkipUses(element)) {
            return null;
        }

        AnnotatedDeclaredType capturedType =
                (AnnotatedDeclaredType) atypeFactory.applyCaptureConversion(type);
        List<AnnotatedTypeParameterBounds> bounds =
                atypeFactory.typeVariablesFromUse(capturedType, element);

        visitor.checkTypeArguments(
                tree,
                bounds,
                capturedType.getTypeArguments(),
                tree.getTypeArguments(),
                element.getSimpleName(),
                element.getTypeParameters());

        @SuppressWarnings(
                "interning:not.interned") // applyCaptureConversion returns the passed type if type
        // does not have wildcards.
        boolean hasCapturedTypeVariables = capturedType != type;
        if (!hasCapturedTypeVariables) {
            return null;
        }

        // Check that the extends bound of the captured type variable is a subtype of the
        // extends bound of the wildcard.
        int numTypeArgs = capturedType.getTypeArguments().size();
        // First create a mapping from captured type variable to its wildcard.
        Map<TypeVariable, AnnotatedTypeMirror> typeVarToWildcard =
                ArrayMap.newArrayMapOrHashMap(numTypeArgs);
        for (int i = 0; i < numTypeArgs; i++) {
            AnnotatedTypeMirror captureTypeArg = capturedType.getTypeArguments().get(i);
            if (TypesUtils.isCapturedTypeVariable(captureTypeArg.getUnderlyingType())
                    && type.getTypeArguments().get(i).getKind() == TypeKind.WILDCARD) {
                AnnotatedTypeVariable capturedTypeVar = (AnnotatedTypeVariable) captureTypeArg;
                AnnotatedWildcardType wildcard =
                        (AnnotatedWildcardType) type.getTypeArguments().get(i);
                typeVarToWildcard.put(capturedTypeVar.getUnderlyingType(), wildcard);
            }
        }

        for (int i = 0; i < numTypeArgs; i++) {
            if (type.getTypeArguments().get(i).getKind() != TypeKind.WILDCARD) {
                continue;
            }
            AnnotatedTypeMirror captureTypeArg = capturedType.getTypeArguments().get(i);
            AnnotatedWildcardType wildcard = (AnnotatedWildcardType) type.getTypeArguments().get(i);
            if (TypesUtils.isCapturedTypeVariable(captureTypeArg.getUnderlyingType())) {
                AnnotatedTypeVariable capturedTypeVar = (AnnotatedTypeVariable) captureTypeArg;
                // Substitute the captured type variables with their wildcards. Without
                // this, the isSubtype check crashes because wildcards aren't comparable
                // with type variables.
                AnnotatedTypeMirror captureTypeVarUB =
                        atypeFactory
                                .getTypeVarSubstitutor()
                                .substituteWithoutCopyingTypeArguments(
                                        typeVarToWildcard, capturedTypeVar.getUpperBound());
                if (!atypeFactory
                        .getTypeHierarchy()
                        .isSubtype(captureTypeVarUB, wildcard.getExtendsBound())) {
                    // For most captured type variables, this will trivially hold, as capturing
                    // incorporated the extends bound of the wildcard into the upper bound of the
                    // type variable.
                    // This will fail if the bound and the wildcard have generic types and there is
                    // no appropriate GLB.
                    // This issues an error for types that cannot be satisfied, because the two
                    // bounds have contradictory requirements.
                    checker.reportError(
                            tree.getTypeArguments().get(i),
                            "type.argument.type.incompatible",
                            element.getTypeParameters().get(i),
                            element.getSimpleName(),
                            wildcard.getExtendsBound(),
                            capturedTypeVar.getUpperBound());
                }
            } else if (AnnotatedTypes.hasExplicitSuperBound(wildcard)) {
                // If the super bound of the wildcard is the same as the upper bound of the
                // type parameter, then javac uses the bound rather than creating a fresh
                // type variable.
                // (See https://bugs.openjdk.org/browse/JDK-8054309.)
                // In this case, the Checker Framework uses the annotations on the super
                // bound of the wildcard and ignores the annotations on the extends bound.
                // For example, Set<@1 ? super @2 Object> will collapse into Set<@2 Object>.
                // So, issue a warning if the annotations on the extends bound are not the
                // same as the annotations on the super bound.
                if (!(atypeFactory
                                .getTypeHierarchy()
                                .isSubtypeShallowEffective(
                                        wildcard.getSuperBound(), wildcard.getExtendsBound())
                        && atypeFactory
                                .getTypeHierarchy()
                                .isSubtypeShallowEffective(
                                        wildcard.getExtendsBound(), wildcard.getSuperBound()))) {
                    checker.reportError(
                            tree.getTypeArguments().get(i),
                            "type.invalid.super.wildcard",
                            wildcard.getExtendsBound(),
                            wildcard.getSuperBound());
                }
            }
        }

        return null;
    }

    @Override
    public Void visitTypeVariable(AnnotatedTypeVariable type, Tree tree) {
        if (visitedNodes.containsKey(type)) {
            return visitedNodes.get(type);
        }

        if (type.isDeclaration() && !areBoundsValid(type.getUpperBound(), type.getLowerBound())) {
            reportInvalidBounds(type, tree);
        }
        AnnotatedTypeVariable useOfTypeVar = type.asUse();
        if (tree instanceof TypeParameterTree) {
            TypeParameterTree typeParameterTree = (TypeParameterTree) tree;
            visitedNodes.put(useOfTypeVar, defaultResult);
            visitTypeParameterBounds(useOfTypeVar, typeParameterTree);
            visitedNodes.put(useOfTypeVar, defaultResult);
            return null;
        }
        return super.visitTypeVariable(useOfTypeVar, tree);
    }

    @Override
    public Void visitWildcard(AnnotatedWildcardType type, Tree tree) {
        if (visitedNodes.containsKey(type)) {
            return visitedNodes.get(type);
        }

        if (!areBoundsValid(type.getExtendsBound(), type.getSuperBound())) {
            reportInvalidBounds(type, tree);
        }

        validateWildCardTargetLocation(type, tree);
        return super.visitWildcard(type, tree);
    }

    /**
     * Returns true if the effective annotations on the upperBound are above (or equal to) those on
     * the lowerBound.
     *
     * @param upperBound the upper bound to check
     * @param lowerBound the lower bound to check
     * @return true if the effective annotations on the upperBound are above (or equal to) those on
     *     the lowerBound
     */
    public boolean areBoundsValid(AnnotatedTypeMirror upperBound, AnnotatedTypeMirror lowerBound) {
        AnnotationMirrorSet upperBoundAnnos =
                AnnotatedTypes.findEffectiveAnnotations(qualHierarchy, upperBound);
        AnnotationMirrorSet lowerBoundAnnos =
                AnnotatedTypes.findEffectiveAnnotations(qualHierarchy, lowerBound);

        if (upperBoundAnnos.size() == lowerBoundAnnos.size()) {
            return atypeFactory
                    .getTypeHierarchy()
                    .isSubtypeShallowEffective(lowerBound, upperBound);
        } else {
            // When upperBoundAnnos.size() != lowerBoundAnnos.size() one of the two bound types will
            // be reported as invalid.  Therefore, we do not do any other comparisons nor do we
            // report a bound.
            return true;
        }
    }

    /**
     * Validate if qualifiers on wildcard are permitted by {@link
     * org.checkerframework.framework.qual.TargetLocations}. Report an error if the actual use of
     * this annotation is not listed in the declared TypeUseLocations in this meta-annotation.
     *
     * @param type the type to check
     * @param tree the tree of this type
     */
    protected void validateWildCardTargetLocation(AnnotatedWildcardType type, Tree tree) {
        if (visitor.ignoreTargetLocations) {
            return;
        }

        for (AnnotationMirror am : type.getSuperBound().getAnnotations()) {
            List<TypeUseLocation> locations =
                    visitor.qualAllowedLocations.get(AnnotationUtils.annotationName(am));
            // @Target({ElementType.TYPE_USE})} together with no @TargetLocations(...) means
            // that the qualifier can be written on any type use.
            // Otherwise, for a valid use of qualifier on the super bound, that qualifier must
            // declare one of these four type-use locations in the @TargetLocations meta-annotation.
            List<TypeUseLocation> lowerLocations =
                    Arrays.asList(
                            TypeUseLocation.ALL,
                            TypeUseLocation.LOWER_BOUND,
                            TypeUseLocation.IMPLICIT_LOWER_BOUND,
                            TypeUseLocation.EXPLICIT_LOWER_BOUND);
            if (locations == null || locations.stream().anyMatch(lowerLocations::contains)) {
                continue;
            }

            checker.reportError(
                    tree,
                    "type.invalid.annotations.on.location",
                    type.getSuperBound().getAnnotations().toString(),
                    "SUPER_WILDCARD");
        }

        for (AnnotationMirror am : type.getExtendsBound().getAnnotations()) {
            List<TypeUseLocation> locations =
                    visitor.qualAllowedLocations.get(AnnotationUtils.annotationName(am));
            List<TypeUseLocation> upperLocations =
                    Arrays.asList(
                            TypeUseLocation.ALL,
                            TypeUseLocation.UPPER_BOUND,
                            TypeUseLocation.IMPLICIT_UPPER_BOUND,
                            TypeUseLocation.EXPLICIT_UPPER_BOUND);
            if (locations == null || locations.stream().anyMatch(upperLocations::contains)) {
                continue;
            }

            checker.reportError(
                    tree,
                    "type.invalid.annotations.on.location",
                    type.getExtendsBound().getAnnotations().toString(),
                    "EXTENDS_WILDCARD");
        }
    }
}
