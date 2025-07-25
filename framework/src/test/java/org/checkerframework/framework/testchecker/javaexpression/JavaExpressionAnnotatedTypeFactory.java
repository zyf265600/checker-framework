package org.checkerframework.framework.testchecker.javaexpression;

import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.testchecker.javaexpression.qual.FEBottom;
import org.checkerframework.framework.testchecker.javaexpression.qual.FETop;
import org.checkerframework.framework.testchecker.javaexpression.qual.FlowExp;
import org.checkerframework.framework.type.MostlyNoElementQualifierHierarchy;
import org.checkerframework.framework.util.QualifierKind;
import org.checkerframework.framework.util.dependenttypes.DependentTypesHelper;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TreeUtils;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.util.Elements;

public class JavaExpressionAnnotatedTypeFactory extends BaseAnnotatedTypeFactory {
    private AnnotationMirror TOP, BOTTOM;

    /** The FlowExp.value field/element. */
    ExecutableElement flowExpValueElement =
            TreeUtils.getMethod(FlowExp.class, "value", 0, processingEnv);

    /**
     * Creates a new JavaExpressionAnnotatedTypeFactory.
     *
     * @param checker the checker
     */
    @SuppressWarnings("this-escape")
    public JavaExpressionAnnotatedTypeFactory(BaseTypeChecker checker) {
        super(checker);
        TOP = AnnotationBuilder.fromClass(elements, FETop.class);
        BOTTOM = AnnotationBuilder.fromClass(elements, FEBottom.class);
        postInit();
    }

    @Override
    protected DependentTypesHelper createDependentTypesHelper() {
        return new DependentTypesHelper(this);
    }

    @Override
    protected JavaExpressionQualifierHierarchy createQualifierHierarchy() {
        return new JavaExpressionQualifierHierarchy(this.getSupportedTypeQualifiers(), elements);
    }

    private class JavaExpressionQualifierHierarchy extends MostlyNoElementQualifierHierarchy {

        /**
         * Create a {@code JavaExpressionQualifierHierarchy}.
         *
         * @param qualifierClasses classes of annotations that are the qualifiers
         * @param elements element utils
         */
        public JavaExpressionQualifierHierarchy(
                Set<Class<? extends Annotation>> qualifierClasses, Elements elements) {
            super(qualifierClasses, elements, JavaExpressionAnnotatedTypeFactory.this);
        }

        @Override
        protected boolean isSubtypeWithElements(
                AnnotationMirror subAnno,
                QualifierKind subKind,
                AnnotationMirror superAnno,
                QualifierKind superKind) {
            List<String> subtypeExpressions =
                    AnnotationUtils.getElementValueArray(
                            subAnno, flowExpValueElement, String.class);
            List<String> supertypeExpressions =
                    AnnotationUtils.getElementValueArray(
                            superAnno, flowExpValueElement, String.class);
            return subtypeExpressions.containsAll(supertypeExpressions)
                    && supertypeExpressions.containsAll(subtypeExpressions);
        }

        @Override
        protected AnnotationMirror leastUpperBoundWithElements(
                AnnotationMirror a1,
                QualifierKind qualifierKind1,
                AnnotationMirror a2,
                QualifierKind qualifierKind2,
                QualifierKind lubKind) {
            if (qualifierKind1.getName() == FEBottom.class.getCanonicalName()) {
                return a2;
            } else if (qualifierKind2.getName() == FEBottom.class.getCanonicalName()) {
                return a1;
            }
            List<String> a1Expressions =
                    AnnotationUtils.getElementValueArray(a1, flowExpValueElement, String.class);
            List<String> a2Expressions =
                    AnnotationUtils.getElementValueArray(a2, flowExpValueElement, String.class);
            if (a1Expressions.containsAll(a2Expressions)
                    && a2Expressions.containsAll(a1Expressions)) {
                return a1;
            }
            return TOP;
        }

        @Override
        protected AnnotationMirror greatestLowerBoundWithElements(
                AnnotationMirror a1,
                QualifierKind qualifierKind1,
                AnnotationMirror a2,
                QualifierKind qualifierKind2,
                QualifierKind glbKind) {
            if (qualifierKind1.getName() == FETop.class.getCanonicalName()) {
                return a2;
            } else if (qualifierKind2.getName() == FETop.class.getCanonicalName()) {
                return a1;
            }
            List<String> a1Expressions =
                    AnnotationUtils.getElementValueArray(a1, flowExpValueElement, String.class);
            List<String> a2Expressions =
                    AnnotationUtils.getElementValueArray(a2, flowExpValueElement, String.class);
            if (a1Expressions.containsAll(a2Expressions)
                    && a2Expressions.containsAll(a1Expressions)) {
                return a1;
            }
            return BOTTOM;
        }
    }
}
