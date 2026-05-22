package com.stavfx.nav3hiltvm.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.ksp.toAnnotationSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Emits the generated `<VMName>_Nav.kt` file containing:
 *  - `<VMName>Hilt` subclass with `@HiltViewModel(assistedFactory = …)` + `@AssistedInject`
 *    constructor mirroring the user's primary ctor, forwarding via `super(...)`. Nested
 *    `@AssistedFactory` interface.
 *  - `<vmStrippedName>Entry` — extension on `EntryProviderScope<NavKey>` that resolves the
 *    subclass through `hiltViewModel` and hands it to the user's `content` lambda as the base
 *    type.
 *
 * Constructor parameter handling: `@Assisted` is preserved on the nav key param; all other
 * annotations on each parameter (Hilt qualifiers like `@ApplicationContext`, `@Named`, etc.) are
 * copied through so Hilt knows how to resolve each dep. The `private val` / `val` modifiers on
 * the user's ctor params are intentionally dropped — those declare properties on the base class,
 * which we don't need on the subclass (it just forwards).
 */
class FactoryGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    fun generate(
        vmClass: KSClassDeclaration,
        primaryCtor: KSFunctionDeclaration,
        navKeyParam: KSValueParameter,
    ) {
        val packageName = vmClass.packageName.asString()
        val vmName = vmClass.simpleName.asString()
        val hiltSubName = "${vmName}Hilt"
        // Strip `ViewModel` suffix and lowercase the first letter, then append `Entry`.
        // MyScreenViewModel → myScreenEntry
        val entryName = vmName.removeSuffix("ViewModel")
            .replaceFirstChar { it.lowercaseChar() } + "Entry"

        val vmTypeName = vmClass.toClassName()
        val hiltSubTypeName = ClassName(packageName, hiltSubName)
        val factoryTypeName = hiltSubTypeName.nestedClass("Factory")
        val keyTypeName = navKeyParam.type.toTypeName()
        val keyParamName = navKeyParam.name?.asString() ?: "navKey"

        val subCtorParams = primaryCtor.parameters.map { param ->
            val pName = param.name?.asString() ?: error("Constructor parameter missing name in $vmName")
            val pType = param.type.toTypeName()
            ParameterSpec.builder(pName, pType).apply {
                param.annotations.forEach { ann ->
                    addAnnotation(ann.toAnnotationSpec())
                }
            }.build()
        }
        val superCallArgs = primaryCtor.parameters.joinToString(", ") {
            it.name?.asString() ?: error("Constructor parameter missing name in $vmName")
        }

        val factoryInterface = TypeSpec.interfaceBuilder("Factory")
            .addAnnotation(AssistedFactoryAnnotation)
            .addFunction(
                FunSpec.builder("create")
                    .addModifiers(KModifier.ABSTRACT)
                    .addParameter(keyParamName, keyTypeName)
                    .returns(hiltSubTypeName)
                    .build()
            )
            .build()

        val hiltViewModelAnnotation = AnnotationSpec.builder(HiltViewModelAnnotation)
            .addMember("assistedFactory = %T::class", factoryTypeName)
            .build()

        val hiltSubclass = TypeSpec.classBuilder(hiltSubName)
            .addAnnotation(hiltViewModelAnnotation)
            .superclass(vmTypeName)
            .apply { primaryCtor.parameters.forEach { _ -> } } // (kept for symmetry; super call below)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addAnnotation(AssistedInjectAnnotation)
                    .addParameters(subCtorParams)
                    .build()
            )
            .addSuperclassConstructorParameter(superCallArgs)
            .addType(factoryInterface)
            .build()

        val composableContent = LambdaTypeName.get(
            parameters = arrayOf(ParameterSpec.unnamed(vmTypeName)),
            returnType = UNIT,
        ).copy(
            annotations = listOf(AnnotationSpec.builder(ComposableAnnotation).build()),
        )

        val entryFn = FunSpec.builder(entryName)
            .receiver(EntryProviderScope.parameterizedBy(NavKey))
            .addParameter(
                ParameterSpec.builder("extraMetadata", MAP.parameterizedBy(STRING, ANY))
                    .defaultValue("emptyMap()")
                    .build()
            )
            .addParameter(ParameterSpec.builder("content", composableContent).build())
            // `entry` is a member of EntryProviderScope (this fn's receiver), so just call it.
            .addStatement(
                "entry<%T>(metadata = { extraMetadata }) { %L ->",
                keyTypeName, keyParamName,
            )
            .addStatement(
                "    content(%M<%T, %T>(creationCallback = { it.create(%L) }))",
                HiltViewModelMember, hiltSubTypeName, factoryTypeName, keyParamName,
            )
            .addStatement("}")
            .build()

        val containingFile = vmClass.containingFile ?: run {
            logger.error("Cannot find containing file for $vmName", vmClass)
            return
        }

        FileSpec.builder(packageName, "${vmName}_Nav")
            .addType(hiltSubclass)
            .addFunction(entryFn)
            .build()
            .writeTo(
                codeGenerator,
                Dependencies(aggregating = false, containingFile),
            )
    }

    private companion object {
        val AssistedFactoryAnnotation = ClassName("dagger.assisted", "AssistedFactory")
        val AssistedInjectAnnotation = ClassName("dagger.assisted", "AssistedInject")
        val HiltViewModelAnnotation =
            ClassName("dagger.hilt.android.lifecycle", "HiltViewModel")
        val ComposableAnnotation = ClassName("androidx.compose.runtime", "Composable")
        val EntryProviderScope = ClassName("androidx.navigation3.runtime", "EntryProviderScope")
        val NavKey = ClassName("androidx.navigation3.runtime", "NavKey")
        val HiltViewModelMember =
            MemberName("androidx.hilt.lifecycle.viewmodel.compose", "hiltViewModel")
    }
}
