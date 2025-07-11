package org.checkerframework.checker.index.inequality;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;

import org.checkerframework.checker.index.BaseAnnotatedTypeFactoryForIndexChecker;
import org.checkerframework.checker.index.OffsetDependentTypesHelper;
import org.checkerframework.checker.index.qual.LessThan;
import org.checkerframework.checker.index.qual.LessThanBottom;
import org.checkerframework.checker.index.qual.LessThanUnknown;
import org.checkerframework.checker.index.upperbound.OffsetEquation;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.value.ValueAnnotatedTypeFactory;
import org.checkerframework.common.value.ValueChecker;
import org.checkerframework.common.value.ValueCheckerUtils;
import org.checkerframework.common.value.qual.ArrayLen;
import org.checkerframework.common.value.qual.ArrayLenRange;
import org.checkerframework.common.value.qual.IntRange;
import org.checkerframework.common.value.qual.IntVal;
import org.checkerframework.dataflow.expression.FieldAccess;
import org.checkerframework.dataflow.expression.JavaExpression;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.ElementQualifierHierarchy;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.util.JavaExpressionParseUtil.JavaExpressionParseException;
import org.checkerframework.framework.util.dependenttypes.DependentTypesHelper;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TreeUtils;
import org.plumelib.util.CollectionsPlume;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Elements;

/** The type factory for the Less Than Checker. */
public class LessThanAnnotatedTypeFactory extends BaseAnnotatedTypeFactoryForIndexChecker {
    /** The @LessThanBottom annotation. */
    private final AnnotationMirror LESS_THAN_BOTTOM =
            AnnotationBuilder.fromClass(elements, LessThanBottom.class);

    /** The @LessThanUnknown annotation. */
    public final AnnotationMirror LESS_THAN_UNKNOWN =
            AnnotationBuilder.fromClass(elements, LessThanUnknown.class);

    /** The LessThan.value argument/element. */
    private final ExecutableElement lessThanValueElement =
            TreeUtils.getMethod(LessThan.class, "value", 0, processingEnv);

    /**
     * Creates a new LessThanAnnotatedTypeFactory.
     *
     * @param checker the type-checker associated with this type factory
     */
    @SuppressWarnings("this-escape")
    public LessThanAnnotatedTypeFactory(BaseTypeChecker checker) {
        super(checker);
        postInit();
    }

    /**
     * Returns the Value Checker's annotated type factory.
     *
     * @return the Value Checker's annotated type factory
     */
    public ValueAnnotatedTypeFactory getValueAnnotatedTypeFactory() {
        return getTypeFactoryOfSubchecker(ValueChecker.class);
    }

    @Override
    protected Set<Class<? extends Annotation>> createSupportedTypeQualifiers() {
        return new LinkedHashSet<>(
                Arrays.asList(LessThan.class, LessThanUnknown.class, LessThanBottom.class));
    }

    @Override
    protected DependentTypesHelper createDependentTypesHelper() {
        // Allows + or - in a @LessThan.
        return new OffsetDependentTypesHelper(this);
    }

    @Override
    protected QualifierHierarchy createQualifierHierarchy() {
        return new LessThanQualifierHierarchy(this.getSupportedTypeQualifiers(), elements);
    }

    /** LessThanQualifierHierarchy. */
    class LessThanQualifierHierarchy extends ElementQualifierHierarchy {

        /**
         * Creates a LessThanQualifierHierarchy from the given classes.
         *
         * @param qualifierClasses classes of annotations that are the qualifiers
         * @param elements element utils
         */
        public LessThanQualifierHierarchy(
                Set<Class<? extends Annotation>> qualifierClasses, Elements elements) {
            super(qualifierClasses, elements, LessThanAnnotatedTypeFactory.this);
        }

        @Override
        public boolean isSubtypeQualifiers(AnnotationMirror subAnno, AnnotationMirror superAnno) {
            List<String> subList = getLessThanExpressions(subAnno);
            if (subList == null) {
                return true;
            }
            List<String> superList = getLessThanExpressions(superAnno);
            if (superList == null) {
                return false;
            }

            return subList.containsAll(superList);
        }

        @Override
        public AnnotationMirror leastUpperBoundQualifiers(
                AnnotationMirror a1, AnnotationMirror a2) {
            if (isSubtypeQualifiers(a1, a2)) {
                return a2;
            } else if (isSubtypeQualifiers(a2, a1)) {
                return a1;
            }

            List<String> a1List = getLessThanExpressions(a1);
            List<String> a2List = getLessThanExpressions(a2);
            a1List.retainAll(a2List); // intersection
            return createLessThanQualifier(a1List);
        }

        @Override
        public AnnotationMirror greatestLowerBoundQualifiers(
                AnnotationMirror a1, AnnotationMirror a2) {
            if (isSubtypeQualifiers(a1, a2)) {
                return a1;
            } else if (isSubtypeQualifiers(a2, a1)) {
                return a2;
            }

            List<String> a1List = getLessThanExpressions(a1);
            List<String> a2List = getLessThanExpressions(a2);
            CollectionsPlume.adjoinAll(a1List, a2List); // union
            return createLessThanQualifier(a1List);
        }
    }

    /**
     * Returns true if {@code left} is less than {@code right}.
     *
     * @param left the first tree to compare
     * @param right the second tree to compare
     * @return is left less than right?
     */
    public boolean isLessThan(Tree left, String right) {
        AnnotatedTypeMirror leftATM = getAnnotatedType(left);
        return isLessThan(leftATM.getAnnotationInHierarchy(LESS_THAN_UNKNOWN), right);
    }

    /**
     * Returns true if {@code left} is less than {@code right}.
     *
     * @param left the first value to compare (an annotation)
     * @param right the second value to compare (an expression)
     * @return is left less than right?
     */
    public boolean isLessThan(AnnotationMirror left, String right) {
        List<String> expressions = getLessThanExpressions(left);
        if (expressions == null) {
            // `left` is @LessThanBottom
            return true;
        }
        return expressions.contains(right);
    }

    /**
     * Returns true if {@code smaller < bigger}.
     *
     * @param smaller the first value to compare
     * @param bigger the second value to compare
     * @param path used to parse expressions strings
     * @return {@code smaller < bigger}, using information from the Value Checker
     */
    public boolean isLessThanByValue(Tree smaller, String bigger, TreePath path) {
        Long smallerValue = ValueCheckerUtils.getMaxValue(smaller, getValueAnnotatedTypeFactory());
        if (smallerValue == null) {
            return false;
        }

        OffsetEquation offsetEquation = OffsetEquation.createOffsetFromJavaExpression(bigger);
        if (offsetEquation.isInt()) {
            // bigger is an int literal
            return smallerValue < offsetEquation.getInt();
        }
        // If bigger is "expression + literal", then smaller < expression + literal
        // can be reduced to smaller - literal < expression + literal - literal
        smallerValue = smallerValue - offsetEquation.getInt();
        offsetEquation =
                offsetEquation.copyAdd(
                        '-', OffsetEquation.createOffsetForInt(offsetEquation.getInt()));

        long minValueOfBigger = getMinValueFromString(offsetEquation.toString(), smaller, path);
        return smallerValue < minValueOfBigger;
    }

    /**
     * Returns the minimum value of {@code expression} at {@code tree}.
     *
     * @param expression the expression whose minimum value to retrieve
     * @param tree where to determine the value
     * @param path the path to {@code tree}
     * @return the minimum value of {@code expression} at {@code tree}
     */
    private long getMinValueFromString(String expression, Tree tree, TreePath path) {
        ValueAnnotatedTypeFactory valueAtypeFactory = getValueAnnotatedTypeFactory();
        JavaExpression expressionJe;
        try {
            expressionJe = valueAtypeFactory.parseJavaExpressionString(expression, path);
        } catch (JavaExpressionParseException e) {
            return Long.MIN_VALUE;
        }

        AnnotationMirror intRange =
                valueAtypeFactory.getAnnotationFromJavaExpression(
                        expressionJe, tree, IntRange.class);
        if (intRange != null) {
            return valueAtypeFactory.getRange(intRange).from;
        }
        AnnotationMirror intValue =
                valueAtypeFactory.getAnnotationFromJavaExpression(expressionJe, tree, IntVal.class);
        if (intValue != null) {
            List<Long> possibleValues = valueAtypeFactory.getIntValues(intValue);
            return Collections.min(possibleValues);
        }

        if (expressionJe instanceof FieldAccess) {
            FieldAccess fieldAccess = ((FieldAccess) expressionJe);
            if (fieldAccess.getReceiver().getType().getKind() == TypeKind.ARRAY) {
                // array.length might not be in the store, so check for the length of the array.
                AnnotationMirror arrayRange =
                        valueAtypeFactory.getAnnotationFromJavaExpression(
                                fieldAccess.getReceiver(), tree, ArrayLenRange.class);
                if (arrayRange != null) {
                    return valueAtypeFactory.getRange(arrayRange).from;
                }
                AnnotationMirror arrayLen =
                        valueAtypeFactory.getAnnotationFromJavaExpression(
                                expressionJe, tree, ArrayLen.class);
                if (arrayLen != null) {
                    List<Integer> possibleValues = valueAtypeFactory.getArrayLength(arrayLen);
                    return Collections.min(possibleValues);
                }
                // Even arrays that we know nothing about must have at least zero length.
                return 0;
            }
        }

        return Long.MIN_VALUE;
    }

    /**
     * Returns true if left is less than or equal to right.
     *
     * @param left the first value to compare
     * @param right the second value to compare
     * @return is left less than or equal to right?
     */
    public boolean isLessThanOrEqual(Tree left, String right) {
        AnnotatedTypeMirror leftATM = getAnnotatedType(left);
        return isLessThanOrEqual(leftATM.getAnnotationInHierarchy(LESS_THAN_UNKNOWN), right);
    }

    /**
     * Returns true if left is less than or equal to right.
     *
     * @param left the first value to compare
     * @param right the second value to compare
     * @return is left less than or equal to right?
     */
    public boolean isLessThanOrEqual(AnnotationMirror left, String right) {
        List<String> expressions = getLessThanExpressions(left);
        if (expressions == null) {
            // left is bottom so it is always less than right.
            return true;
        }
        if (expressions.contains(right)) {
            return true;
        }
        for (String expression : expressions) {
            if (expression.endsWith(" + 1")
                    && expression.substring(0, expression.length() - 4).equals(right)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a sorted, modifiable list of expressions that {@code expression} is less than. If the
     * {@code expression} is annotated with {@link LessThanBottom}, null is returned.
     *
     * @param expression an expression
     * @return expressions that {@code expression} is less than
     */
    public @Nullable List<String> getLessThanExpressions(ExpressionTree expression) {
        AnnotatedTypeMirror annotatedTypeMirror = getAnnotatedType(expression);
        return getLessThanExpressions(
                annotatedTypeMirror.getAnnotationInHierarchy(LESS_THAN_UNKNOWN));
    }

    /**
     * Creates a less than qualifier given the expressions.
     *
     * <p>If expressions is null, {@link LessThanBottom} is returned. If expressions is empty,
     * {@link LessThanUnknown} is returned. Otherwise, {@code @LessThan(expressions)} is returned.
     *
     * @param expressions a list of expressions
     * @return a @LessThan qualifier with the given arguments
     */
    public AnnotationMirror createLessThanQualifier(@Nullable List<String> expressions) {
        if (expressions == null) {
            return LESS_THAN_BOTTOM;
        } else if (expressions.isEmpty()) {
            return LESS_THAN_UNKNOWN;
        } else {
            AnnotationBuilder builder = new AnnotationBuilder(processingEnv, LessThan.class);
            builder.setValue("value", expressions);
            return builder.build();
        }
    }

    /** Returns {@code @LessThan(expression)}. */
    public AnnotationMirror createLessThanQualifier(String expression) {
        return createLessThanQualifier(Collections.singletonList(expression));
    }

    /**
     * If the annotation is LessThan, returns a list of expressions in the annotation. If the
     * annotation is {@link LessThanBottom}, returns null. If the annotation is {@link
     * LessThanUnknown}, returns the empty list.
     *
     * @param annotation an annotation from the same hierarchy as LessThan
     * @return the list of expressions in the annotation
     */
    public @Nullable List<String> getLessThanExpressions(AnnotationMirror annotation) {
        if (AnnotationUtils.areSameByName(
                annotation, "org.checkerframework.checker.index.qual.LessThanBottom")) {
            return null;
        } else if (AnnotationUtils.areSameByName(
                annotation, "org.checkerframework.checker.index.qual.LessThanUnknown")) {
            return Collections.emptyList();
        } else {
            // The annotation is @LessThan.
            return AnnotationUtils.getElementValueArray(
                    annotation, lessThanValueElement, String.class);
        }
    }
}
