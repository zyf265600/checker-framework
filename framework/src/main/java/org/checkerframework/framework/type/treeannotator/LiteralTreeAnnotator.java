package org.checkerframework.framework.type.treeannotator;

import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.Tree;

import org.checkerframework.framework.qual.LiteralKind;
import org.checkerframework.framework.qual.QualifierForLiterals;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.GenericAnnotatedTypeFactory;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.type.typeannotator.DefaultForTypeAnnotator;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationMirrorSet;
import org.checkerframework.javacutil.BugInCF;
import org.plumelib.util.StringsPlume;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.type.TypeMirror;

/**
 * Adds annotations to a type based on the contents of a tree. This class applies annotations
 * specified by {@link org.checkerframework.framework.qual.QualifierForLiterals}; it is designed to
 * be added to a {@link ListTreeAnnotator} via {@link
 * GenericAnnotatedTypeFactory#createTreeAnnotator()}
 *
 * <p>{@link LiteralTreeAnnotator} does not traverse trees deeply.
 *
 * @see TreeAnnotator
 */
public class LiteralTreeAnnotator extends TreeAnnotator {

    /* The following three fields are mappings from a particular AST kind,
     * AST Class, or String literal pattern to the set of AnnotationMirrors
     * that should be defaulted.
     * There can be at most one qualifier per qualifier hierarchy.
     * For type systems with single top qualifiers, the sets will always contain
     * at most one element.
     */
    /** Maps AST kind to the set of AnnotationMirrors that should be defaulted. */
    private final Map<Tree.Kind, AnnotationMirrorSet> treeKinds;

    /** Maps AST class to the set of AnnotationMirrors that should be defaulted. */
    private final Map<Class<?>, AnnotationMirrorSet> treeClasses;

    /** Maps String literal pattern to the set of AnnotationMirrors that should be defaulted. */
    private final IdentityHashMap<Pattern, AnnotationMirrorSet> stringPatterns;

    /** The qualifier hierarchy. */
    protected final QualifierHierarchy qualHierarchy;

    /**
     * Map of {@link LiteralKind}s to {@link Tree.Kind}s. This is here and not in LiteralKinds
     * because LiteralKind is in the checker-qual.jar which cannot depend on classes, such as
     * Tree.Kind, that are in the tools.jar
     */
    private static final Map<LiteralKind, Tree.Kind> literalKindToTreeKind =
            new EnumMap<>(LiteralKind.class);

    static {
        literalKindToTreeKind.put(LiteralKind.BOOLEAN, Tree.Kind.BOOLEAN_LITERAL);
        literalKindToTreeKind.put(LiteralKind.CHAR, Tree.Kind.CHAR_LITERAL);
        literalKindToTreeKind.put(LiteralKind.DOUBLE, Tree.Kind.DOUBLE_LITERAL);
        literalKindToTreeKind.put(LiteralKind.FLOAT, Tree.Kind.FLOAT_LITERAL);
        literalKindToTreeKind.put(LiteralKind.INT, Tree.Kind.INT_LITERAL);
        literalKindToTreeKind.put(LiteralKind.LONG, Tree.Kind.LONG_LITERAL);
        literalKindToTreeKind.put(LiteralKind.NULL, Tree.Kind.NULL_LITERAL);
        literalKindToTreeKind.put(LiteralKind.STRING, Tree.Kind.STRING_LITERAL);
    }

    /**
     * Creates a {@link LiteralTreeAnnotator} for the given {@code atypeFactory}.
     *
     * @param atypeFactory the type factory to make an annotator for
     */
    @SuppressWarnings("this-escape")
    public LiteralTreeAnnotator(AnnotatedTypeFactory atypeFactory) {
        super(atypeFactory);
        this.treeKinds = new EnumMap<>(Tree.Kind.class);
        this.treeClasses = new HashMap<>();
        this.stringPatterns = new IdentityHashMap<>();

        this.qualHierarchy = atypeFactory.getQualifierHierarchy();

        // Get type qualifiers from the checker.
        Set<Class<? extends Annotation>> quals = atypeFactory.getSupportedTypeQualifiers();

        // For each qualifier, read the @QualifierForLiterals annotation and put its contents into
        // maps.
        for (Class<? extends Annotation> qual : quals) {
            QualifierForLiterals forLiterals = qual.getAnnotation(QualifierForLiterals.class);
            if (forLiterals == null) {
                continue;
            }

            AnnotationMirror theQual =
                    AnnotationBuilder.fromClass(atypeFactory.getElementUtils(), qual);
            for (LiteralKind literalKind : forLiterals.value()) {
                addLiteralKind(literalKind, theQual);
            }

            for (String regex : forLiterals.stringPatterns()) {
                addStringPattern(regex, theQual);
            }

            if (forLiterals.value().length == 0 && forLiterals.stringPatterns().length == 0) {
                addLiteralKind(LiteralKind.ALL, theQual);
            }
        }
    }

    /**
     * Adds standard qualifiers for literals. Currently sets the null literal to bottom if no other
     * default is set for null literals. Also, see {@link
     * DefaultForTypeAnnotator#addStandardDefaults()}.
     *
     * @return this
     */
    public LiteralTreeAnnotator addStandardLiteralQualifiers() {
        // Set null to bottom if no other qualifier is given.
        if (!treeKinds.containsKey(Tree.Kind.NULL_LITERAL)) {
            for (AnnotationMirror bottom : qualHierarchy.getBottomAnnotations()) {
                addLiteralKind(LiteralKind.NULL, bottom);
            }
            return this;
        }
        AnnotationMirrorSet tops = qualHierarchy.getTopAnnotations();
        AnnotationMirrorSet defaultForNull = treeKinds.get(Tree.Kind.NULL_LITERAL);
        if (tops.size() == defaultForNull.size()) {
            return this;
        }
        for (AnnotationMirror top : tops) {
            if (qualHierarchy.findAnnotationInHierarchy(defaultForNull, top) == null) {
                defaultForNull.add(qualHierarchy.getBottomAnnotation(top));
            }
        }
        return this;
    }

    /**
     * Added a rule for a particular {@link LiteralKind}
     *
     * @param literalKind {@code LiteralKind} that should be defaulted to {@code theQual}
     * @param theQual the {@code AnnotationMirror} that should be applied to the {@code literalKind}
     */
    public void addLiteralKind(LiteralKind literalKind, AnnotationMirror theQual) {
        if (literalKind == LiteralKind.ALL) {
            for (LiteralKind iterLiteralKind : LiteralKind.allLiteralKinds()) {
                addLiteralKind(iterLiteralKind, theQual);
            }
        } else if (literalKind == LiteralKind.PRIMITIVE) {
            for (LiteralKind iterLiteralKind : LiteralKind.primitiveLiteralKinds()) {
                addLiteralKind(iterLiteralKind, theQual);
            }
        } else {
            Tree.Kind treeKind = literalKindToTreeKind.get(literalKind);
            if (treeKind != null) {
                addTreeKind(treeKind, theQual);
            } else {
                throw new BugInCF("LiteralKind " + literalKind + " is not mapped to a Tree.Kind.");
            }
        }
    }

    /**
     * Added a rule for a particular {@link com.sun.source.tree.Tree.Kind}
     *
     * @param treeKind {@code Tree.Kind} that should be implicited to {@code theQual}
     * @param theQual the {@code AnnotationMirror} that should be applied to the {@code treeKind}
     */
    private void addTreeKind(Tree.Kind treeKind, AnnotationMirror theQual) {
        boolean res = qualHierarchy.updateMappingToMutableSet(treeKinds, treeKind, theQual);
        if (!res) {
            throw new BugInCF(
                    "LiteralTreeAnnotator: tried to add mapping %s=%s to %s",
                    treeKind, theQual, treeKinds);
        }
    }

    /**
     * Added a rule for all String literals that match the given pattern.
     *
     * @param regex regex to match Strings against
     * @param theQual {@code AnnotationMirror} to apply to Strings that match the regex
     * @see #addStringPattern(Pattern,AnnotationMirror)
     */
    public void addStringPattern(String regex, AnnotationMirror theQual) {
        boolean res =
                qualHierarchy.updateMappingToMutableSet(
                        stringPatterns, Pattern.compile(regex), theQual);
        if (!res) {
            throw new BugInCF(
                    "LiteralTreeAnnotator: invalid update of stringPatterns "
                            + stringPatterns
                            + " at "
                            + regex
                            + " with "
                            + theQual);
        }
    }

    /**
     * Added a rule for all String literals that match the given pattern.
     *
     * @param pattern pattern to match Strings against
     * @param theQual {@code AnnotationMirror} to apply to Strings that match the pattern
     * @see #addStringPattern(String,AnnotationMirror)
     */
    public void addStringPattern(Pattern pattern, AnnotationMirror theQual) {
        boolean res = qualHierarchy.updateMappingToMutableSet(stringPatterns, pattern, theQual);
        if (!res) {
            throw new BugInCF(
                    "LiteralTreeAnnotator: invalid update of stringPatterns "
                            + stringPatterns
                            + " at "
                            + pattern
                            + " with "
                            + theQual);
        }
    }

    @Override
    public Void defaultAction(Tree tree, AnnotatedTypeMirror type) {
        if (tree == null || type == null) {
            return null;
        }

        // If this tree's kind is in treeKinds, annotate the type.

        // If this tree's class or any of its interfaces are in treeClasses, annotate the type, and
        // if it was an interface add a mapping for it to treeClasses.
        if (treeKinds.containsKey(tree.getKind())) {
            AnnotationMirrorSet fnd = treeKinds.get(tree.getKind());
            type.addMissingAnnotations(fnd);
        } else if (!treeClasses.isEmpty()) {
            Class<? extends Tree> t = tree.getClass();
            if (treeClasses.containsKey(t)) {
                AnnotationMirrorSet fnd = treeClasses.get(t);
                type.addMissingAnnotations(fnd);
            }
            for (Class<?> c : t.getInterfaces()) {
                if (treeClasses.containsKey(c)) {
                    AnnotationMirrorSet fnd = treeClasses.get(c);
                    type.addMissingAnnotations(fnd);
                    treeClasses.put(t, treeClasses.get(c));
                }
            }
        }
        return null;
    }

    /** Go through the string patterns and add the greatest lower bound of all matching patterns. */
    @Override
    public Void visitLiteral(LiteralTree tree, AnnotatedTypeMirror type) {
        if (!stringPatterns.isEmpty() && tree.getKind() == Tree.Kind.STRING_LITERAL) {
            List<Set<? extends AnnotationMirror>> matches = new ArrayList<>();
            List<Set<? extends AnnotationMirror>> nonMatches = new ArrayList<>();

            String string = (String) tree.getValue();
            for (Map.Entry<Pattern, AnnotationMirrorSet> entry : stringPatterns.entrySet()) {
                Pattern pattern = entry.getKey();
                AnnotationMirrorSet sam = entry.getValue();
                if (pattern.matcher(string).matches()) {
                    matches.add(sam);
                } else {
                    nonMatches.add(sam);
                }
            }
            if (!matches.isEmpty()) {
                TypeMirror tm = type.getUnderlyingType();
                Set<? extends AnnotationMirror> res = matches.get(0);
                for (Set<? extends AnnotationMirror> sam : matches) {
                    res = qualHierarchy.greatestLowerBoundsShallow(res, tm, sam, tm);
                }
                // Verify that res is not a subtype of any type in nonMatches
                for (Set<? extends AnnotationMirror> sam : nonMatches) {
                    if (qualHierarchy.isSubtypeShallow(res, sam, tm)) {
                        String matchesOnePerLine = "";
                        for (Set<? extends AnnotationMirror> match : matches) {
                            matchesOnePerLine += System.lineSeparator() + "     " + match;
                        }
                        throw new BugInCF(
                                StringsPlume.joinLines(
                                        "Bug in @QualifierForLiterals(stringpatterns=...) in type"
                                                + " hierarchy definition:",
                                        " the glb of `matches` for \"" + string + "\" is " + res,
                                        " which is a subtype of " + sam,
                                        " whose pattern does not match \"" + string + "\".",
                                        "  matches = " + matchesOnePerLine,
                                        "  nonMatches = " + nonMatches));
                    }
                }
                type.addAnnotations(res);
            }
        }
        return super.visitLiteral(tree, type);
    }
}
