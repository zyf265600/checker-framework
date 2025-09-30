package org.checkerframework.checker.nullness;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;

import org.checkerframework.checker.initialization.InitializationFieldAccessAnnotatedTypeFactory;
import org.checkerframework.checker.initialization.InitializationFieldAccessSubchecker;
import org.checkerframework.checker.initialization.InitializationFieldAccessTreeAnnotator;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;
import org.checkerframework.checker.signature.qual.FullyQualifiedName;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.expression.FieldAccess;
import org.checkerframework.dataflow.expression.JavaExpression;
import org.checkerframework.dataflow.expression.LocalVariable;
import org.checkerframework.dataflow.expression.ThisReference;
import org.checkerframework.dataflow.util.NodeUtils;
import org.checkerframework.framework.flow.CFAbstractAnalysis;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedArrayType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedDeclaredType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedNoType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedPrimitiveType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedTypeVariable;
import org.checkerframework.framework.type.GenericAnnotatedTypeFactory;
import org.checkerframework.framework.type.NoElementQualifierHierarchy;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.type.treeannotator.ListTreeAnnotator;
import org.checkerframework.framework.type.treeannotator.LiteralTreeAnnotator;
import org.checkerframework.framework.type.treeannotator.PropagationTreeAnnotator;
import org.checkerframework.framework.type.treeannotator.TreeAnnotator;
import org.checkerframework.framework.type.typeannotator.DefaultForTypeAnnotator;
import org.checkerframework.framework.type.typeannotator.ListTypeAnnotator;
import org.checkerframework.framework.type.typeannotator.TypeAnnotator;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypesUtils;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;

/** The annotated type factory for the nullness type-system. */
public class NullnessNoInitAnnotatedTypeFactory
        extends GenericAnnotatedTypeFactory<
                NullnessNoInitValue,
                NullnessNoInitStore,
                NullnessNoInitTransfer,
                NullnessNoInitAnalysis> {

    /**
     * Runtime toggle: skip the {@code hasEffectiveAnnotation(NONNULL)} fast-path. Controlled via
     * JVM system property {@code -Dcf.skipNonnullFastPath=true}.
     */
    private static final boolean SKIP_NONNULL_FASTPATH =
            Boolean.getBoolean("cf.skipNonnullFastPath");

    /** The @{@link NonNull} annotation. */
    protected final AnnotationMirror NONNULL = AnnotationBuilder.fromClass(elements, NonNull.class);

    /** The @{@link Nullable} annotation. */
    protected final AnnotationMirror NULLABLE =
            AnnotationBuilder.fromClass(elements, Nullable.class);

    /** The @{@link PolyNull} annotation. */
    protected final AnnotationMirror POLYNULL =
            AnnotationBuilder.fromClass(elements, PolyNull.class);

    /** The @{@link MonotonicNonNull} annotation. */
    protected final AnnotationMirror MONOTONIC_NONNULL =
            AnnotationBuilder.fromClass(elements, MonotonicNonNull.class);

    /** Handles invocations of {@link java.lang.System#getProperty(String)}. */
    protected final SystemGetPropertyHandler systemGetPropertyHandler;

    /** Determines the nullness type of calls to {@link java.util.Collection#toArray()}. */
    protected final CollectionToArrayHeuristics collectionToArrayHeuristics;

    /** The Class.getCanonicalName() method. */
    protected final ExecutableElement classGetCanonicalName;

    /** The Arrays.copyOf() methods that operate on arrays of references. */
    private final List<ExecutableElement> copyOfMethods;

    /** Cache for the nullness annotations. */
    protected final Set<Class<? extends Annotation>> nullnessAnnos;

    /** The Map.get method. */
    private final ExecutableElement mapGet =
            TreeUtils.getMethod("java.util.Map", "get", 1, processingEnv);

    // List is in alphabetical order.  If you update it, also update
    // ../../../../../../../../docs/manual/nullness-checker.tex
    // and make a pull request for variables NONNULL_ANNOTATIONS and BASE_COPYABLE_ANNOTATIONS in
    // https://github.com/rzwitserloot/lombok/blob/master/src/core/lombok/core/handlers/HandlerUtil.java .
    // Avoid changes to the string constants by ShadowJar relocate by using "start".toString() +
    // "rest".
    // Keep the original string constant in a comment to allow searching for it.
    /** Aliases for {@code @Nonnull}. */
    @SuppressWarnings({
        "signature:argument.type.incompatible", // Class names intentionally obfuscated
        "signature:assignment.type.incompatible" // Class names intentionally obfuscated
    })
    private static final List<@FullyQualifiedName String> NONNULL_ALIASES =
            Arrays.<@FullyQualifiedName String>asList(
                    // https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/annotation/NonNull.java
                    // https://developer.android.com/reference/androidx/annotation/NonNull
                    "android.annotation.NonNull",
                    // https://android.googlesource.com/platform/frameworks/support/+/master/annotations/src/main/java/android/support/annotation/NonNull.java
                    // https://developer.android.com/reference/android/support/annotation/NonNull
                    "android.support.annotation.NonNull",
                    // https://android.googlesource.com/platform/tools/metalava/+/9ad32fadc5a22e1357c82b447e33ec7fecdcd8c1/stub-annotations/src/main/java/android/support/annotation/RecentlyNonNull.java
                    "android.support.annotation.RecentlyNonNull",
                    // https://android.googlesource.com/platform/frameworks/support/+/master/annotations/src/main/java/androidx/annotation/NonNull.java
                    "androidx.annotation.NonNull",
                    // https://android.googlesource.com/platform/tools/metalava/+/master/stub-annotations/src/main/java/androidx/annotation/RecentlyNonNull.java
                    "androidx.annotation.RecentlyNonNull",
                    // https://android.googlesource.com/platform/sdk/+/66fcecc/common/src/com/android/annotations/NonNull.java
                    "com.android.annotations.NonNull",
                    // https://github.com/firebase/firebase-android-sdk/blob/master/firebase-database/src/main/java/com/google/firebase/database/annotations/NotNull.java
                    // "com.google.firebase.database.annotations.NotNull",
                    "com.go".toString() + "ogle.firebase.database.annotations.NotNull",
                    // https://github.com/firebase/firebase-admin-java/blob/master/src/main/java/com/google/firebase/internal/NonNull.java
                    // "com.google.firebase.internal.NonNull",
                    "com.go".toString() + "ogle.firebase.internal.NonNull",
                    // https://github.com/mongodb/mongo-java-driver/blob/master/driver-core/src/main/com/mongodb/lang/NonNull.java
                    "com.mongodb.lang.NonNull",
                    // https://github.com/eclipse-ee4j/jaxb-istack-commons/blob/master/istack-commons/runtime/src/main/java/com/sun/istack/NotNull.java
                    "com.sun.istack.NotNull",
                    // https://github.com/openjdk/jdk8/blob/master/jaxws/src/share/jaxws_classes/com/sun/istack/internal/NotNull.java
                    "com.sun.istack.internal.NotNull",
                    // https://github.com/pingidentity/ldapsdk/blob/master/src/com/unboundid/util/NotNull.java
                    "com.unboundid.util.NotNull",
                    // https://findbugs.sourceforge.net/api/edu/umd/cs/findbugs/annotations/NonNull.html
                    "edu.umd.cs.findbugs.annotations.NonNull",
                    // https://github.com/micrometer-metrics/micrometer/blob/main/micrometer-core/src/main/java/io/micrometer/core/lang/NonNull.java
                    "io.micrometer.core.lang.NonNull",
                    // https://github.com/micronaut-projects/micronaut-core/blob/master/core/src/main/java/io/micronaut/core/annotation/NonNull.java
                    "io.micronaut.core.annotation.NonNull",
                    // https://github.com/ReactiveX/RxJava/blob/2.x/src/main/java/io/reactivex/annotations/NonNull.java
                    "io.reactivex.annotations.NonNull",
                    // https://github.com/ReactiveX/RxJava/blob/3.x/src/main/java/io/reactivex/rxjava3/annotations/NonNull.java
                    "io.reactivex.rxjava3.annotations.NonNull",
                    // https://github.com/jakartaee/common-annotations-api/blob/master/api/src/main/java/jakarta/annotation/Nonnull.java
                    "jakarta.annotation.Nonnull",
                    // https://jakarta.ee/specifications/bean-validation/3.0/apidocs/jakarta/validation/constraints/notnull
                    "jakarta.validation.constraints.NotNull",
                    // https://jcp.org/en/jsr/detail?id=305; no documentation at
                    // https://www.javadoc.io/doc/com.google.code.findbugs/jsr305/3.0.1/javax/annotation/Nonnull.html
                    "javax.annotation.Nonnull",
                    // https://javaee.github.io/javaee-spec/javadocs/javax/validation/constraints/NotNull.html
                    "javax.validation.constraints.NotNull",
                    // https://android.googlesource.com/platform/libcore/+/master/luni/src/main/java/libcore/util/NonNull.java
                    "libcore.util.NonNull",
                    // https://github.com/projectlombok/lombok/blob/master/src/core/lombok/NonNull.java
                    "lombok.NonNull",
                    // https://github.com/raphw/byte-buddy/blob/master/byte-buddy-agent/src/main/java/net/bytebuddy/agent/utility/nullability/NeverNull.java
                    "net.bytebuddy.agent.utility.nullability.NeverNull",
                    // https://github.com/raphw/byte-buddy/blob/master/byte-buddy-dep/src/main/java/net/bytebuddy/utility/nullability/NeverNull.java
                    "net.bytebuddy.utility.nullability.NeverNull",
                    // Removed in ANTLR 4.6.
                    // https://github.com/antlr/antlr4/blob/master/runtime/Java/src/org/antlr/v4/runtime/misc/NotNull.java
                    "org.antlr.v4.runtime.misc.NotNull",
                    // https://search.maven.org/artifact/org.checkerframework/checker-compat-qual/2.5.5/jar
                    "org.checkerframework.checker.nullness.compatqual.NonNullDecl",
                    "org.checkerframework.checker.nullness.compatqual.NonNullType",
                    // https://source.chromium.org/chromium/chromium/src/+/main:build/android/java/src/org/chromium/build/annotations/OptimizeAsNonNull.java
                    "org.chromium.build.annotations.OptimizeAsNonNull",
                    // https://janino-compiler.github.io/janino/apidocs/org/codehaus/commons/nullanalysis/NotNull.html
                    "org.codehaus.commons.nullanalysis.NotNull",
                    // https://help.eclipse.org/neon/index.jsp?topic=/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/annotation/NonNull.html
                    // https://git.eclipse.org/c/jdt/eclipse.jdt.core.git/tree/org.eclipse.jdt.annotation/src/org/eclipse/jdt/annotation/NonNull.java
                    "org.eclipse.jdt.annotation.NonNull",
                    // https://github.com/eclipse/jgit/blob/master/org.eclipse.jgit/src/org/eclipse/jgit/annotations/NonNull.java
                    "org.eclipse.jgit.annotations.NonNull",
                    // https://github.com/eclipse/lsp4j/blob/main/org.eclipse.lsp4j.jsonrpc/src/main/java/org/eclipse/lsp4j/jsonrpc/validation/NonNull.java
                    "org.eclipse.lsp4j.jsonrpc.validation.NonNull",
                    // https://github.com/JetBrains/intellij-community/blob/master/platform/annotations/java8/src/org/jetbrains/annotations/NotNull.java
                    // https://www.jetbrains.com/help/idea/nullable-and-notnull-annotations.html
                    "org.jetbrains.annotations.NotNull",
                    // http://svn.code.sf.net/p/jmlspecs/code/JMLAnnotations/trunk/src/org/jmlspecs/annotation/NonNull.java
                    "org.jmlspecs.annotation.NonNull",
                    // https://github.com/jspecify/jspecify/blob/main/src/main/java/org/jspecify/annotations/NonNull.java
                    "org.jspecify.annotations.NonNull",
                    // 2022-11-17: Deprecated old package location, remove after some grace period
                    // https://github.com/jspecify/jspecify/tree/main/src/main/java/org/jspecify/nullness
                    "org.jspecify.nullness.NonNull",
                    // http://bits.netbeans.org/dev/javadoc/org-netbeans-api-annotations-common/org/netbeans/api/annotations/common/NonNull.html
                    "org.netbeans.api.annotations.common.NonNull",
                    // https://github.com/spring-projects/spring-framework/blob/master/spring-core/src/main/java/org/springframework/lang/NonNull.java
                    "org.springframework.lang.NonNull",
                    // https://github.com/reactor/reactor-core/blob/main/reactor-core/src/main/java/reactor/util/annotation/NonNull.java
                    "reactor.util.annotation.NonNull");

    // List is in alphabetical order.  If you update it, also update
    // ../../../../../../../../docs/manual/nullness-checker.tex .
    // See more comments with NONNULL_ALIASES above.
    /** Aliases for {@code @Nullable}. */
    @SuppressWarnings({
        "signature:argument.type.incompatible", // Class names intentionally obfuscated
        "signature:assignment.type.incompatible" // Class names intentionally obfuscated
    })
    private static final List<@FullyQualifiedName String> NULLABLE_ALIASES =
            Arrays.<@FullyQualifiedName String>asList(
                    // https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/annotation/Nullable.java
                    // https://developer.android.com/reference/androidx/annotation/Nullable
                    "android.annotation.Nullable",
                    // https://android.googlesource.com/platform/frameworks/support/+/master/annotations/src/main/java/android/support/annotation/Nullable.java
                    // https://developer.android.com/reference/android/support/annotation/Nullable
                    "android.support.annotation.Nullable",
                    // https://android.googlesource.com/platform/tools/metalava/+/9ad32fadc5a22e1357c82b447e33ec7fecdcd8c1/stub-annotations/src/main/java/android/support/annotation/RecentlyNullable.java
                    "android.support.annotation.RecentlyNullable",
                    // https://android.googlesource.com/platform/frameworks/support/+/master/annotations/src/main/java/androidx/annotation/Nullable.java
                    "androidx.annotation.Nullable",
                    // https://android.googlesource.com/platform/tools/metalava/+/master/stub-annotations/src/main/java/androidx/annotation/RecentlyNullable.java
                    "androidx.annotation.RecentlyNullable",
                    // https://android.googlesource.com/platform/sdk/+/66fcecc/common/src/com/android/annotations/Nullable.java
                    "com.android.annotations.Nullable",
                    // https://github.com/lpantano/java_seqbuster/blob/master/AdRec/src/adrec/com/beust/jcommander/internal/Nullable.java
                    "com.beust.jcommander.internal.Nullable",
                    // https://github.com/cloudendpoints/endpoints-java/blob/master/endpoints-framework/src/main/java/com/google/api/server/spi/config/Nullable.java
                    // "com.google.api.server.spi.config.Nullable",
                    "com.go".toString() + "ogle.api.server.spi.config.Nullable",
                    // https://github.com/firebase/firebase-android-sdk/blob/master/firebase-database/src/main/java/com/google/firebase/database/annotations/Nullable.java
                    // "com.google.firebase.database.annotations.Nullable",
                    "com.go".toString() + "ogle.firebase.database.annotations.Nullable",
                    // https://github.com/firebase/firebase-admin-java/blob/master/src/main/java/com/google/firebase/internal/Nullable.java
                    // "com.google.firebase.internal.Nullable",
                    "com.go".toString() + "ogle.firebase.internal.Nullable",
                    // https://gerrit.googlesource.com/gerrit/+/refs/heads/master/java/com/google/gerrit/common/Nullable.java
                    // "com.google.gerrit.common.Nullable",
                    "com.go".toString() + "ogle.gerrit.common.Nullable",
                    //
                    // "com.google.protobuf.Internal.ProtoMethodAcceptsNullParameter",
                    "com.go".toString() + "ogle.protobuf.Internal.ProtoMethodAcceptsNullParameter",
                    //
                    // "com.google.protobuf.Internal.ProtoMethodMayReturnNull",
                    "com.go".toString() + "ogle.protobuf.Internal.ProtoMethodMayReturnNull",
                    // https://github.com/mongodb/mongo-java-driver/blob/master/driver-core/src/main/com/mongodb/lang/Nullable.java
                    "com.mongodb.lang.Nullable",
                    // https://github.com/eclipse-ee4j/jaxb-istack-commons/blob/master/istack-commons/runtime/src/main/java/com/sun/istack/Nullable.java
                    "com.sun.istack.Nullable",
                    // https://github.com/openjdk/jdk8/blob/master/jaxws/src/share/jaxws_classes/com/sun/istack/internal/Nullable.java
                    "com.sun.istack.internal.Nullable",
                    // https://github.com/pingidentity/ldapsdk/blob/master/src/com/unboundid/util/Nullable.java
                    "com.unboundid.util.Nullable",
                    // https://findbugs.sourceforge.net/api/edu/umd/cs/findbugs/annotations/CheckForNull.html
                    "edu.umd.cs.findbugs.annotations.CheckForNull",
                    // https://findbugs.sourceforge.net/api/edu/umd/cs/findbugs/annotations/Nullable.html
                    "edu.umd.cs.findbugs.annotations.Nullable",
                    // https://findbugs.sourceforge.net/api/edu/umd/cs/findbugs/annotations/PossiblyNull.html
                    "edu.umd.cs.findbugs.annotations.PossiblyNull",
                    // https://findbugs.sourceforge.net/api/edu/umd/cs/findbugs/annotations/UnknownNullness.html
                    "edu.umd.cs.findbugs.annotations.UnknownNullness",
                    // https://github.com/micrometer-metrics/micrometer/blob/main/micrometer-core/src/main/java/io/micrometer/core/lang/Nullable.java
                    "io.micrometer.core.lang.Nullable",
                    // https://github.com/micronaut-projects/micronaut-core/blob/master/core/src/main/java/io/micronaut/core/annotation/Nullable.java
                    "io.micronaut.core.annotation.Nullable",
                    // https://github.com/ReactiveX/RxJava/blob/2.x/src/main/java/io/reactivex/annotations/Nullable.java
                    "io.reactivex.annotations.Nullable",
                    // https://github.com/ReactiveX/RxJava/blob/3.x/src/main/java/io/reactivex/rxjava3/annotations/Nullable.java
                    "io.reactivex.rxjava3.annotations.Nullable",
                    // https://github.com/eclipse-vertx/vertx-codegen/blob/master/src/main/java/io/vertx/codegen/annotations/Nullable.java
                    "io.vertx.codegen.annotations.Nullable",
                    // https://github.com/jakartaee/common-annotations-api/blob/master/api/src/main/java/jakarta/annotation/Nullable.java
                    "jakarta.annotation.Nullable",
                    // https://jcp.org/en/jsr/detail?id=305; no documentation at
                    // https://www.javadoc.io/doc/com.google.code.findbugs/jsr305/3.0.1/javax/annotation/Nullable.html
                    "javax.annotation.CheckForNull",
                    "javax.annotation.Nullable",
                    // https://github.com/Pragmatists/JUnitParams/blob/master/src/main/java/junitparams/converters/Nullable.java
                    "junitparams.converters.Nullable",
                    // https://android.googlesource.com/platform/libcore/+/master/luni/src/main/java/libcore/util/Nullable.java
                    "libcore.util.Nullable",
                    // https://github.com/raphw/byte-buddy/blob/master/byte-buddy-agent/src/main/java/net/bytebuddy/agent/utility/nullability/AlwaysNull.java
                    "net.bytebuddy.agent.utility.nullability.AlwaysNull",
                    // https://github.com/raphw/byte-buddy/blob/master/byte-buddy-agent/src/main/java/net/bytebuddy/agent/utility/nullability/MaybeNull.java
                    "net.bytebuddy.agent.utility.nullability.MaybeNull",
                    // https://github.com/raphw/byte-buddy/blob/master/byte-buddy-agent/src/main/java/net/bytebuddy/agent/utility/nullability/UnknownNull.java
                    "net.bytebuddy.agent.utility.nullability.UnknownNull",
                    // https://github.com/raphw/byte-buddy/blob/master/byte-buddy-dep/src/main/java/net/bytebuddy/utility/nullability/AlwaysNull.java
                    "net.bytebuddy.utility.nullability.AlwaysNull",
                    // https://github.com/raphw/byte-buddy/blob/master/byte-buddy-dep/src/main/java/net/bytebuddy/utility/nullability/MaybeNull.java
                    "net.bytebuddy.utility.nullability.MaybeNull",
                    // https://github.com/raphw/byte-buddy/blob/master/byte-buddy-dep/src/main/java/net/bytebuddy/utility/nullability/UnknownNull.java
                    "net.bytebuddy.utility.nullability.UnknownNull",
                    // https://github.com/apache/avro/blob/master/lang/java/avro/src/main/java/org/apache/avro/reflect/Nullable.java
                    // "org.apache.avro.reflect.Nullable",
                    "org.apa".toString() + "che.avro.reflect.Nullable",
                    // https://github.com/apache/cxf/blob/master/rt/frontend/jaxrs/src/main/java/org/apache/cxf/jaxrs/ext/Nullable.java
                    // "org.apache.cxf.jaxrs.ext.Nullable",
                    "org.apa".toString() + "che.cxf.jaxrs.ext.Nullable",
                    // https://github.com/gatein/gatein-shindig/blob/master/java/common/src/main/java/org/apache/shindig/common/Nullable.java
                    // "org.apache.shindig.common.Nullable",
                    "org.apa".toString() + "che.shindig.common.Nullable",
                    // https://search.maven.org/search?q=a:checker-compat-qual
                    "org.checkerframework.checker.nullness.compatqual.NullableDecl",
                    "org.checkerframework.checker.nullness.compatqual.NullableType",
                    // https://source.chromium.org/chromium/chromium/src/+/main:build/android/java/src/org/chromium/build/annotations/Nullable.java
                    "org.chromium.build.annotations.Nullable",
                    // https://janino-compiler.github.io/janino/apidocs/org/codehaus/commons/nullanalysis/Nullable.html
                    "org.codehaus.commons.nullanalysis.Nullable",
                    // https://help.eclipse.org/neon/index.jsp?topic=/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/annotation/Nullable.html
                    // https://git.eclipse.org/c/jdt/eclipse.jdt.core.git/tree/org.eclipse.jdt.annotation/src/org/eclipse/jdt/annotation/Nullable.java
                    "org.eclipse.jdt.annotation.Nullable",
                    // https://github.com/eclipse/jgit/blob/master/org.eclipse.jgit/src/org/eclipse/jgit/annotations/Nullable.java
                    "org.eclipse.jgit.annotations.Nullable",
                    // https://github.com/JetBrains/intellij-community/blob/master/platform/annotations/java8/src/org/jetbrains/annotations/Nullable.java
                    // https://www.jetbrains.com/help/idea/nullable-and-notnull-annotations.html
                    "org.jetbrains.annotations.Nullable",
                    // https://github.com/JetBrains/java-annotations/blob/master/java8/src/main/java/org/jetbrains/annotations/UnknownNullability.java
                    "org.jetbrains.annotations.UnknownNullability",
                    // http://svn.code.sf.net/p/jmlspecs/code/JMLAnnotations/trunk/src/org/jmlspecs/annotation/Nullable.java
                    "org.jmlspecs.annotation.Nullable",
                    // https://github.com/jspecify/jspecify/blob/main/src/main/java/org/jspecify/annotations/Nullable.java
                    "org.jspecify.annotations.Nullable",
                    // 2022-11-17: Deprecated old package location, remove after some grace period
                    // https://github.com/jspecify/jspecify/tree/main/src/main/java/org/jspecify/nullness
                    "org.jspecify.nullness.Nullable",
                    "org.jspecify.nullness.NullnessUnspecified",
                    // http://bits.netbeans.org/dev/javadoc/org-netbeans-api-annotations-common/org/netbeans/api/annotations/common/CheckForNull.html
                    "org.netbeans.api.annotations.common.CheckForNull",
                    // http://bits.netbeans.org/dev/javadoc/org-netbeans-api-annotations-common/org/netbeans/api/annotations/common/NullAllowed.html
                    "org.netbeans.api.annotations.common.NullAllowed",
                    // http://bits.netbeans.org/dev/javadoc/org-netbeans-api-annotations-common/org/netbeans/api/annotations/common/NullUnknown.html
                    "org.netbeans.api.annotations.common.NullUnknown",
                    // https://github.com/spring-projects/spring-framework/blob/master/spring-core/src/main/java/org/springframework/lang/Nullable.java
                    "org.springframework.lang.Nullable",
                    // https://github.com/reactor/reactor-core/blob/main/reactor-core/src/main/java/reactor/util/annotation/Nullable.java
                    "reactor.util.annotation.Nullable");

    // List is in alphabetical order.  If you update it, also update
    // ../../../../../../../../docs/manual/nullness-checker.tex .
    // See more comments with NONNULL_ALIASES above.
    /** Aliases for {@code @PolyNull}. */
    @SuppressWarnings({
        "signature:argument.type.incompatible", // Class names intentionally obfuscated
        "signature:assignment.type.incompatible" // Class names intentionally obfuscated
    })
    private static final List<@FullyQualifiedName String> POLYNULL_ALIASES =
            Arrays.<@FullyQualifiedName String>asList(
                    // "com.google.protobuf.Internal.ProtoPassThroughNullness",
                    "com.go".toString() + "ogle.protobuf.Internal.ProtoPassThroughNullness");

    /**
     * Creates a NullnessAnnotatedTypeFactory.
     *
     * @param checker the associated {@link NullnessNoInitSubchecker}
     */
    @SuppressWarnings("this-escape")
    public NullnessNoInitAnnotatedTypeFactory(BaseTypeChecker checker) {
        super(checker);

        Set<Class<? extends Annotation>> tempNullnessAnnos = new LinkedHashSet<>(4);
        tempNullnessAnnos.add(NonNull.class);
        tempNullnessAnnos.add(MonotonicNonNull.class);
        tempNullnessAnnos.add(Nullable.class);
        tempNullnessAnnos.add(PolyNull.class);
        nullnessAnnos = Collections.unmodifiableSet(tempNullnessAnnos);

        NONNULL_ALIASES.forEach(annotation -> addAliasedTypeAnnotation(annotation, NONNULL));
        NULLABLE_ALIASES.forEach(annotation -> addAliasedTypeAnnotation(annotation, NULLABLE));
        POLYNULL_ALIASES.forEach(annotation -> addAliasedTypeAnnotation(annotation, POLYNULL));

        // Add compatibility annotations:
        addAliasedTypeAnnotation(
                "org.checkerframework.checker.nullness.compatqual.PolyNullDecl", POLYNULL);
        addAliasedTypeAnnotation(
                "org.checkerframework.checker.nullness.compatqual.MonotonicNonNullDecl",
                MONOTONIC_NONNULL);
        addAliasedTypeAnnotation(
                "org.checkerframework.checker.nullness.compatqual.PolyNullType", POLYNULL);
        addAliasedTypeAnnotation(
                "org.checkerframework.checker.nullness.compatqual.MonotonicNonNullType",
                MONOTONIC_NONNULL);

        if (checker.getUltimateParentChecker().getBooleanOption("jspecifyNullMarkedAlias", true)) {
            AnnotationMirror nullMarkedDefaultQual =
                    new AnnotationBuilder(processingEnv, DefaultQualifier.class)
                            .setValue("value", NonNull.class)
                            .setValue(
                                    "locations",
                                    new TypeUseLocation[] {TypeUseLocation.UPPER_BOUND})
                            .setValue("applyToSubpackages", false)
                            .build();
            addAliasedDeclAnnotation(
                    "org.jspecify.annotations.NullMarked",
                    DefaultQualifier.class.getCanonicalName(),
                    nullMarkedDefaultQual);

            // 2022-11-17: Deprecated old package location, remove after some grace period
            addAliasedDeclAnnotation(
                    "org.jspecify.nullness.NullMarked",
                    DefaultQualifier.class.getCanonicalName(),
                    nullMarkedDefaultQual);
        }

        boolean permitClearProperty =
                checker.getLintOption(
                        NullnessChecker.LINT_PERMITCLEARPROPERTY,
                        NullnessChecker.LINT_DEFAULT_PERMITCLEARPROPERTY);
        systemGetPropertyHandler =
                new SystemGetPropertyHandler(processingEnv, this, permitClearProperty);

        classGetCanonicalName =
                TreeUtils.getMethod("java.lang.Class", "getCanonicalName", 0, processingEnv);
        copyOfMethods =
                Arrays.asList(
                        TreeUtils.getMethod(
                                "java.util.Arrays", "copyOf", processingEnv, "T[]", "int"),
                        TreeUtils.getMethod("java.util.Arrays", "copyOf", 3, processingEnv));

        postInit();

        // do this last, as it might use the factory again.
        this.collectionToArrayHeuristics = new CollectionToArrayHeuristics(checker, this);
    }

    @Override
    public NullnessNoInitSubchecker getChecker() {
        return (NullnessNoInitSubchecker) checker;
    }

    @Override
    protected Set<Class<? extends Annotation>> createSupportedTypeQualifiers() {
        return new LinkedHashSet<>(
                Arrays.asList(
                        Nullable.class, MonotonicNonNull.class, NonNull.class, PolyNull.class));
    }

    /**
     * For types of left-hand side of an assignment, this method replaces {@link PolyNull} with
     * {@link Nullable} (or with {@link NonNull} if the org.checkerframework.dataflow analysis has
     * determined that this is allowed soundly. For example:
     *
     * <pre> @PolyNull String foo(@PolyNull String param) {
     *    if (param == null) {
     *        //  @PolyNull is really @Nullable, so change
     *        // the type of param to @Nullable.
     *        param = null;
     *    }
     *    return param;
     * }
     * </pre>
     *
     * @param lhsType type to replace whose polymorphic qualifier will be replaced
     * @param context tree used to get dataflow value
     */
    protected void replacePolyQualifier(AnnotatedTypeMirror lhsType, Tree context) {
        if (lhsType.hasAnnotation(PolyNull.class)) {
            NullnessNoInitValue inferred = getInferredValueFor(context);
            if (inferred != null) {
                if (inferred.isPolyNullNonNull) {
                    lhsType.replaceAnnotation(NONNULL);
                } else if (inferred.isPolyNullNull) {
                    lhsType.replaceAnnotation(NULLABLE);
                }
            }
        }
    }

    @Override
    protected NullnessNoInitAnalysis createFlowAnalysis() {
        return new NullnessNoInitAnalysis(checker, this);
    }

    @Override
    public NullnessNoInitTransfer createFlowTransferFunction(
            CFAbstractAnalysis<NullnessNoInitValue, NullnessNoInitStore, NullnessNoInitTransfer>
                    analysis) {
        return new NullnessNoInitTransfer((NullnessNoInitAnalysis) analysis);
    }

    @Override
    protected ParameterizedExecutableType methodFromUse(
            MethodInvocationTree tree, boolean inferTypeArgs) {
        ParameterizedExecutableType mType = super.methodFromUse(tree, inferTypeArgs);
        AnnotatedExecutableType method = mType.executableType;

        // Special cases for method invocations with specific arguments.
        systemGetPropertyHandler.handle(tree, method);
        collectionToArrayHeuristics.handle(tree, method);
        // `MyClass.class.getCanonicalName()` is non-null.
        if (TreeUtils.isMethodInvocation(tree, classGetCanonicalName, processingEnv)) {
            ExpressionTree receiver = ((MemberSelectTree) tree.getMethodSelect()).getExpression();
            if (TreeUtils.isClassLiteral(receiver)) {
                AnnotatedTypeMirror type = method.getReturnType();
                type.replaceAnnotation(NONNULL);
            }
        }

        return mType;
    }

    @Override
    public void adaptGetClassReturnTypeToReceiver(
            AnnotatedExecutableType getClassType,
            AnnotatedTypeMirror receiverType,
            ExpressionTree tree) {

        super.adaptGetClassReturnTypeToReceiver(getClassType, receiverType, tree);

        // Make the captured wildcard always @NonNull, regardless of the declared type.

        AnnotatedDeclaredType returnAdt = (AnnotatedDeclaredType) getClassType.getReturnType();
        List<AnnotatedTypeMirror> typeArgs = returnAdt.getTypeArguments();
        AnnotatedTypeVariable classWildcardArg = (AnnotatedTypeVariable) typeArgs.get(0);
        classWildcardArg.getUpperBound().replaceAnnotation(NONNULL);
    }

    @Override
    public AnnotatedTypeMirror getMethodReturnType(MethodTree m, ReturnTree r) {
        AnnotatedTypeMirror result = super.getMethodReturnType(m, r);
        replacePolyQualifier(result, r);
        return result;
    }

    @Override
    public boolean isNotFullyInitializedReceiver(MethodTree methodDeclTree) {
        InitializationFieldAccessAnnotatedTypeFactory initFactory =
                getChecker()
                        .getTypeFactoryOfSubcheckerOrNull(
                                InitializationFieldAccessSubchecker.class);
        if (initFactory == null) {
            // init checker is deactivated.
            return super.isNotFullyInitializedReceiver(methodDeclTree);
        }
        return initFactory.isNotFullyInitializedReceiver(methodDeclTree);
    }

    @Override
    public AnnotatedTypeMirror getAnnotatedTypeBefore(JavaExpression expr, ExpressionTree tree) {
        InitializationFieldAccessAnnotatedTypeFactory initFactory =
                getChecker()
                        .getTypeFactoryOfSubcheckerOrNull(
                                InitializationFieldAccessSubchecker.class);
        if (initFactory == null) {
            // init checker is deactivated.
            return super.getAnnotatedTypeBefore(expr, tree);
        }
        if (expr instanceof FieldAccess) {
            FieldAccess fa = (FieldAccess) expr;
            JavaExpression receiver = fa.getReceiver();
            TypeMirror declaringClass = fa.getField().getEnclosingElement().asType();
            AnnotatedTypeMirror receiverType;

            if (receiver instanceof LocalVariable) {
                Element receiverElem = ((LocalVariable) receiver).getElement();
                receiverType = initFactory.getAnnotatedType(receiverElem);
            } else if (receiver instanceof ThisReference) {
                receiverType = initFactory.getSelfType(tree);
            } else {
                return super.getAnnotatedTypeBefore(expr, tree);
            }

            if (initFactory.isInitializedForFrame(receiverType, declaringClass)) {
                AnnotatedTypeMirror declared = getAnnotatedType(fa.getField());
                AnnotatedTypeMirror refined = super.getAnnotatedTypeBefore(expr, tree);
                AnnotatedTypeMirror res = AnnotatedTypeMirror.createType(fa.getType(), this, false);
                // If the expression is initialized, then by definition, it has at least its
                // declared annotation.
                // Assuming the correctness of the Nullness Checker's type refinement,
                // it also has its refined annotation.
                // We thus use the GLB of those two annotations.
                res.addAnnotations(
                        qualHierarchy.greatestLowerBoundsShallow(
                                declared.getAnnotations(),
                                declared.getUnderlyingType(),
                                refined.getAnnotations(),
                                refined.getUnderlyingType()));
                return res;
            }
        }

        // Is there anything better we could do?
        // Ideally, we would turn the expression string into a Tree or Element
        // instead of a JavaExpression, so we could use
        // atypeFactory.getAnnotatedType on the whole expression,
        // but that doesn't seem possible.
        return super.getAnnotatedTypeBefore(expr, tree);
    }

    @Override
    protected DefaultForTypeAnnotator createDefaultForTypeAnnotator() {
        DefaultForTypeAnnotator defaultForTypeAnnotator = new DefaultForTypeAnnotator(this);
        defaultForTypeAnnotator.addAtmClass(AnnotatedNoType.class, NONNULL);
        defaultForTypeAnnotator.addAtmClass(AnnotatedPrimitiveType.class, NONNULL);
        return defaultForTypeAnnotator;
    }

    @Override
    protected void addAnnotationsFromDefaultForType(
            @Nullable Element element, AnnotatedTypeMirror type) {
        if (element != null
                && element.getKind() == ElementKind.LOCAL_VARIABLE
                && type.getKind().isPrimitive()) {
            // Always apply the DefaultQualifierForUse for primitives.
            super.addAnnotationsFromDefaultForType(null, type);
        } else {
            super.addAnnotationsFromDefaultForType(element, type);
        }
    }

    @Override
    protected TreeAnnotator createTreeAnnotator() {
        // Don't call super.createTreeAnnotator because the default tree annotators are incorrect
        // for the Nullness Checker.
        List<TreeAnnotator> annotators = new ArrayList<>(3);
        // annotators.add(new DebugListTreeAnnotator(new Tree.Kind[]
        // {Tree.Kind.CONDITIONAL_EXPRESSION}));
        annotators.add(new InitializationFieldAccessTreeAnnotator(this));
        annotators.add(new NullnessPropagationTreeAnnotator(this));
        annotators.add(new LiteralTreeAnnotator(this));
        return new ListTreeAnnotator(annotators);
    }

    /** Adds nullness-specific propagation rules */
    // Would this be valid to move into CommitmentTreeAnnotator?
    protected class NullnessPropagationTreeAnnotator extends PropagationTreeAnnotator {

        /**
         * Creates a NullnessPropagationTreeAnnotator.
         *
         * @param atypeFactory this factory
         */
        public NullnessPropagationTreeAnnotator(AnnotatedTypeFactory atypeFactory) {
            super(atypeFactory);
        }

        @Override
        public Void visitTypeCast(TypeCastTree tree, AnnotatedTypeMirror type) {
            if (type.getKind().isPrimitive()) {
                // If a @Nullable expression is cast to a primitive, then an unboxing.of.nullable
                // error is issued.  Treat the cast as if it were annotated as @NonNull to avoid an
                // "type.invalid.annotations.on.use" error.
                type.addMissingAnnotation(NONNULL);
            }
            return super.visitTypeCast(tree, type);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree tree, AnnotatedTypeMirror type) {
            Element elt = TreeUtils.elementFromUse(tree);
            assert elt != null;

            // Make primitive variable @NonNull in case the Initialization Checker
            // considers it uninitialized.
            if (TypesUtils.isPrimitive(type.getUnderlyingType())) {
                type.replaceAnnotation(NONNULL);
            }

            return null;
        }

        @Override
        public Void visitVariable(VariableTree tree, AnnotatedTypeMirror type) {
            Element elt = TreeUtils.elementFromDeclaration(tree);
            if (elt.getKind() == ElementKind.EXCEPTION_PARAMETER) {
                // case 9. exception parameter
                type.addMissingAnnotation(NONNULL);
            }
            return null;
        }

        @Override
        public Void visitIdentifier(IdentifierTree tree, AnnotatedTypeMirror type) {

            Element elt = TreeUtils.elementFromUse(tree);
            assert elt != null;

            if (elt.getKind() == ElementKind.EXCEPTION_PARAMETER) {
                // TODO: It's surprising that we have to do this in both visitVariable and
                // visitIdentifier. This should already be handled by applying the defaults anyway.
                // case 9. exception parameter
                type.replaceAnnotation(NONNULL);
            }

            // Make primitive variable @NonNull in case the Initialization Checker
            // considers it uninitialized.
            if (TypesUtils.isPrimitive(type.getUnderlyingType())) {
                type.replaceAnnotation(NONNULL);
            }

            return null;
        }

        // The result of a binary operation is always non-null.
        @Override
        public Void visitBinary(BinaryTree tree, AnnotatedTypeMirror type) {
            type.replaceAnnotation(NONNULL);
            return null;
        }

        // The result of a compound operation is always non-null.
        @Override
        public Void visitCompoundAssignment(CompoundAssignmentTree tree, AnnotatedTypeMirror type) {
            super.visitCompoundAssignment(tree, type);
            type.replaceAnnotation(NONNULL);
            return null;
        }

        // The result of a unary operation is always non-null.
        @Override
        public Void visitUnary(UnaryTree tree, AnnotatedTypeMirror type) {
            type.replaceAnnotation(NONNULL);
            return null;
        }

        // The result of newly allocated structures is always non-null,
        // explicit nullable annotations are left intact for the visitor to inspect.
        @Override
        public Void visitNewClass(NewClassTree tree, AnnotatedTypeMirror type) {
            if (!SKIP_NONNULL_FASTPATH && type.hasEffectiveAnnotation(NONNULL)) {
                return null;
            }
            type.addMissingAnnotation(NONNULL);
            return null;
        }

        // The result of newly allocated structures is always non-null,
        // explicit nullable annotations are left intact for the visitor to inspect.
        @Override
        public Void visitNewArray(NewArrayTree tree, AnnotatedTypeMirror type) {
            super.visitNewArray(tree, type);
            type.addMissingAnnotation(NONNULL);
            return null;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree tree, AnnotatedTypeMirror type) {
            if (TreeUtils.isMethodInvocation(tree, copyOfMethods, processingEnv)) {
                List<? extends ExpressionTree> args = tree.getArguments();
                ExpressionTree lengthArg = args.get(1);
                if (TreeUtils.isArrayLengthAccess(lengthArg)) {
                    // TODO: This syntactic test may not be not correct if the array expression has
                    // a side effect that affects the array length.  This code could require that
                    // the expression has no method calls, assignments, etc.
                    ExpressionTree arrayArg = args.get(0);
                    if (TreeUtils.sameTree(
                            arrayArg, ((MemberSelectTree) lengthArg).getExpression())) {
                        AnnotatedArrayType arrayArgType =
                                (AnnotatedArrayType) getAnnotatedType(arrayArg);
                        AnnotatedTypeMirror arrayArgComponentType = arrayArgType.getComponentType();
                        // Maybe this call is only necessary if argNullness is @NonNull.
                        ((AnnotatedArrayType) type)
                                .getComponentType()
                                .replaceAnnotations(arrayArgComponentType.getAnnotations());
                    }
                }
            }
            return super.visitMethodInvocation(tree, type);
        }
    }

    @Override
    protected TypeAnnotator createTypeAnnotator() {
        return new ListTypeAnnotator(super.createTypeAnnotator(), new NullnessTypeAnnotator(this));
    }

    /**
     * This type annotator ensures that constructor return types are NONNULL, unless there is an
     * explicit different annotation.
     */
    protected class NullnessTypeAnnotator extends TypeAnnotator {

        /**
         * Creates a new NullnessTypeAnnotator.
         *
         * @param atypeFactory this factory
         */
        public NullnessTypeAnnotator(NullnessNoInitAnnotatedTypeFactory atypeFactory) {
            super(atypeFactory);
        }

        @Override
        public Void visitExecutable(AnnotatedExecutableType t, Void p) {
            Void result = super.visitExecutable(t, p);
            Element elem = t.getElement();
            if (elem.getKind() == ElementKind.CONSTRUCTOR) {
                AnnotatedDeclaredType returnType = (AnnotatedDeclaredType) t.getReturnType();
                returnType.addMissingAnnotation(NONNULL);
            }
            return result;
        }
    }

    /**
     * Returns the list of annotations of the non-null type system.
     *
     * @return the list of annotations of the non-null type system
     */
    public Set<Class<? extends Annotation>> getNullnessAnnotations() {
        return nullnessAnnos;
    }

    @Override
    protected QualifierHierarchy createQualifierHierarchy() {
        return new NoElementQualifierHierarchy(getSupportedTypeQualifiers(), elements, this);
    }

    /**
     * Returns true if some annotation on the given type, or in the given list, is a nullness
     * annotation such as @NonNull, @Nullable, @MonotonicNonNull, etc.
     *
     * <p>This method ignores aliases of nullness annotations that are declaration annotations,
     * because they may apply to inner types.
     *
     * @param annoTrees a list of annotations that the Java parser attached to the variable/method
     *     declaration; null if this type is not from such a location. This is a list of extra
     *     annotations to check, in addition to those on the type.
     * @param typeTree the type whose annotations to test
     * @return true if some annotation is a nullness annotation
     */
    protected boolean containsNullnessAnnotation(
            @Nullable List<? extends AnnotationTree> annoTrees, Tree typeTree) {
        List<? extends AnnotationTree> annos =
                TreeUtils.getExplicitAnnotationTrees(annoTrees, typeTree);
        return containsNullnessAnnotation(annos);
    }

    /**
     * Returns true if some annotation in the given list is a nullness annotation such
     * as @NonNull, @Nullable, @MonotonicNonNull, etc.
     *
     * <p>This method ignores aliases of nullness annotations that are declaration annotations,
     * because they may apply to inner types.
     *
     * <p>Clients that are processing a field or variable definition, or a method return type,
     * should call {@link #containsNullnessAnnotation(List, Tree)} instead.
     *
     * @param annoTrees a list of annotations to check
     * @return true if some annotation is a nullness annotation
     * @see #containsNullnessAnnotation(List, Tree)
     */
    protected boolean containsNullnessAnnotation(List<? extends AnnotationTree> annoTrees) {
        for (AnnotationTree annoTree : annoTrees) {
            AnnotationMirror am = TreeUtils.annotationFromAnnotationTree(annoTree);
            if (isNullnessAnnotation(am) && AnnotationUtils.isTypeUseAnnotation(am)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the given annotation is a nullness annotation such as {@code @NonNull},
     * {@code @Nullable}, {@code @MonotonicNonNull}, {@code @PolyNull}, or an alias thereof.
     *
     * @param am an annotation
     * @return true if the given annotation is a nullness annotation
     */
    protected boolean isNullnessAnnotation(AnnotationMirror am) {
        return isNonNullOrAlias(am)
                || isNullableOrAlias(am)
                || AnnotationUtils.areSameByName(am, MONOTONIC_NONNULL)
                || isPolyNullOrAlias(am);
    }

    /**
     * Returns true if the given annotation is {@code @NonNull} or an alias for it.
     *
     * @param am an annotation
     * @return true if the given annotation is {@code @NonNull} or an alias for it
     */
    protected boolean isNonNullOrAlias(AnnotationMirror am) {
        AnnotationMirror canonical = canonicalAnnotation(am);
        if (canonical != null) {
            am = canonical;
        }
        return AnnotationUtils.areSameByName(am, NONNULL);
    }

    /**
     * Returns true if the given annotation is {@code @Nullable} or an alias for it.
     *
     * @param am an annotation
     * @return true if the given annotation is {@code @Nullable} or an alias for it
     */
    protected boolean isNullableOrAlias(AnnotationMirror am) {
        AnnotationMirror canonical = canonicalAnnotation(am);
        if (canonical != null) {
            am = canonical;
        }
        return AnnotationUtils.areSameByName(am, NULLABLE);
    }

    /**
     * Returns true if the given annotation is {@code @PolyNull} or an alias for it.
     *
     * @param am an annotation
     * @return true if the given annotation is {@code @PolyNull} or an alias for it
     */
    protected boolean isPolyNullOrAlias(AnnotationMirror am) {
        AnnotationMirror canonical = canonicalAnnotation(am);
        if (canonical != null) {
            am = canonical;
        }
        return AnnotationUtils.areSameByName(am, POLYNULL);
    }

    // If a reference field has no initializer, then its default value is null.  Treat that as
    // @MonotonicNonNull rather than as @Nullable.
    @Override
    public AnnotatedTypeMirror getDefaultValueAnnotatedType(TypeMirror typeMirror) {
        AnnotatedTypeMirror result = super.getDefaultValueAnnotatedType(typeMirror);
        if (getAnnotationByClass(result.getAnnotations(), Nullable.class) != null) {
            result.replaceAnnotation(MONOTONIC_NONNULL);
        }
        return result;
    }

    /** A non-null reference to an object stays non-null under mutation. */
    @Override
    public boolean isImmutable(TypeMirror type) {
        return true;
    }

    /* NO-AFU
    // If
    //  1. rhs is @Nullable
    //  2. lhs is a field of this
    //  3. in a constructor, initializer block, or field initializer
    // then change rhs to @MonotonicNonNull.
    @Override
    public void wpiAdjustForUpdateField(
        Tree lhsTree, Element element, String fieldName, AnnotatedTypeMirror rhsATM) {
      // Synthetic variable names contain "#". Ignore them.
      if (!rhsATM.hasAnnotation(Nullable.class) || fieldName.contains("#")) {
        return;
      }
      TreePath lhsPath = getPath(lhsTree);
      TypeElement enclosingClassOfLhs =
          TreeUtils.elementFromDeclaration(TreePathUtil.enclosingClass(lhsPath));
      ClassSymbol enclosingClassOfField = ((VarSymbol) element).enclClass();
      if (enclosingClassOfLhs.equals(enclosingClassOfField) && TreePathUtil.inConstructor(lhsPath)) {
        rhsATM.replaceAnnotation(MONOTONIC_NONNULL);
      }
    }

    // If
    //  1. rhs is @MonotonicNonNull
    // then change rhs to @Nullable
    @Override
    public void wpiAdjustForUpdateNonField(AnnotatedTypeMirror rhsATM) {
      if (rhsATM.hasAnnotation(MonotonicNonNull.class)) {
        rhsATM.replaceAnnotation(NULLABLE);
      }
    }

    @Override
    public boolean wpiShouldInferTypesForReceivers() {
      // All receivers must be non-null, or the dereference involved in
      // the method call would fail (and cause an NPE). So, WPI should not
      // infer non-null or nullable annotations on method receiver parameters.
      return false;
    }

    // This implementation overrides the superclass implementation to:
    //  * check for @MonotonicNonNull
    //  * output @RequiresNonNull rather than @RequiresQualifier.
    @Override
    protected @Nullable AnnotationMirror createRequiresOrEnsuresQualifier(
        String expression,
        AnnotationMirror qualifier,
        AnnotatedTypeMirror declaredType,
        Analysis.BeforeOrAfter preOrPost,
        @Nullable List<AnnotationMirror> preconds) {
      // TODO: This does not handle the possibility that the user set a different default
      // annotation.
      if (!(declaredType.hasAnnotation(NULLABLE)
          || declaredType.hasAnnotation(POLYNULL)
          || declaredType.hasAnnotation(MONOTONIC_NONNULL))) {
        return null;
      }

      if (preOrPost == BeforeOrAfter.AFTER
          && declaredType.hasAnnotation(MONOTONIC_NONNULL)
          && preconds.contains(requiresNonNullAnno(expression))) {
        // The postcondition is implied by the precondition and the field being
        // @MonotonicNonNull.
        return null;
      }

      if (AnnotationUtils.areSameByName(
          qualifier, "org.checkerframework.checker.nullness.qual.NonNull")) {
        if (preOrPost == BeforeOrAfter.BEFORE) {
          return requiresNonNullAnno(expression);
        } else {
          return ensuresNonNullAnno(expression);
        }

        if (preOrPost == BeforeOrAfter.AFTER
                && declaredType.hasAnnotation(MONOTONIC_NONNULL)
                && preconds.contains(requiresNonNullAnno(expression))) {
            // The postcondition is implied by the precondition and the field being
            // @MonotonicNonNull.
            return null;
        }

        if (AnnotationUtils.areSameByName(
                qualifier, "org.checkerframework.checker.nullness.qual.NonNull")) {
            if (preOrPost == BeforeOrAfter.BEFORE) {
                return requiresNonNullAnno(expression);
            } else {
                return ensuresNonNullAnno(expression);
            }
        }
        return super.createRequiresOrEnsuresQualifier(
                expression, qualifier, declaredType, preOrPost, preconds);
    }
    */

    /* NO-AFU
     * Returns a {@code RequiresNonNull("...")} annotation for the given expression.
     *
     * @param expression an expression
     * @return a {@code RequiresNonNull("...")} annotation for the given expression
     */
    /* NO-AFU
    private AnnotationMirror requiresNonNullAnno(String expression) {
        AnnotationBuilder builder = new AnnotationBuilder(processingEnv, RequiresNonNull.class);
        builder.setValue("value", new String[] {expression});
        AnnotationMirror am = builder.build();
        return am;
    }
    */

    /* NO-AFU
     * Returns a {@code EnsuresNonNull("...")} annotation for the given expression.
     *
     * @param expression an expression
     * @return a {@code EnsuresNonNull("...")} annotation for the given expression
     */
    /* NO-AFU
    private AnnotationMirror ensuresNonNullAnno(String expression) {
        AnnotationBuilder builder = new AnnotationBuilder(processingEnv, EnsuresNonNull.class);
        builder.setValue("value", new String[] {expression});
        AnnotationMirror am = builder.build();
        return am;
    }
    */

    /**
     * Returns true if {@code node} is an invocation of Map.get.
     *
     * @param node a CFG node
     * @return true if {@code node} is an invocation of Map.get
     */
    public boolean isMapGet(Node node) {
        return NodeUtils.isMethodInvocation(node, mapGet, getProcessingEnv());
    }
}
