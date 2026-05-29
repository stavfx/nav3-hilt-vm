/*
 * Copyright 2026 Stav Raviv
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stavfx.nav3hiltvm.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Visibility
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
 *  - `<VMName>_HiltNavArgs` subclass with `@HiltViewModel(assistedFactory = …)` +
 *    `@AssistedInject` constructor mirroring the user's primary ctor, forwarding via
 *    `super(...)`. Nested `@AssistedFactory` interface named `Factory`.
 *  - Two `<vmStrippedName>Entry` extensions on `EntryProviderScope<NavKey>`, both taking an
 *    `extraMetadata: Map<String, Any> = emptyMap()` that is forwarded to the underlying
 *    `entry` call. The canonical overload resolves the subclass via `hiltViewModel` and hands
 *    `(vm, navKey)` to the user's `content` lambda (as the base VM type). The convenience
 *    overload takes a `(vm)`-only `content` and delegates to the canonical one.
 *
 * Constructor parameter handling: the user's `@NavArg` marker is dropped and replaced with
 * Dagger's real `@Assisted` on the nav key param of the generated subclass (so its
 * `@AssistedInject` ctor is well-formed). All other annotations on each parameter (Hilt
 * qualifiers like `@ApplicationContext`, `@Named`, etc.) are copied through so Hilt knows how
 * to resolve each dep. The `private val` / `val` modifiers on the user's ctor params are
 * intentionally dropped — those declare properties on the base class, which we don't need on
 * the subclass (it just forwards).
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
        val hiltSubName = "${vmName}_HiltNavArgs"
        // Strip `ViewModel` suffix and lowercase the first letter, then append `Entry`.
        // MyScreenViewModel → myScreenEntry
        val entryName = vmName.removeSuffix("ViewModel")
            .replaceFirstChar { it.lowercaseChar() } + "Entry"

        val vmTypeName = vmClass.toClassName()
        val hiltSubTypeName = ClassName(packageName, hiltSubName)
        // Mirror the user's VM visibility onto everything we generate. A subclass can't be more
        // visible than the class it extends (e.g. a public subclass of an internal VM won't
        // compile), and the entry functions expose the base VM type in their signatures, so they
        // carry the same constraint.
        val visibility = vmClass.visibilityModifier()
        val factoryTypeName = hiltSubTypeName.nestedClass("Factory")
        val keyTypeName = navKeyParam.type.toTypeName()
        val keyParamName = navKeyParam.name?.asString() ?: "navKey"

        val subCtorParams = primaryCtor.parameters.map { param ->
            val pName = param.name?.asString() ?: error("Constructor parameter missing name in $vmName")
            val pType = param.type.toTypeName()
            val isNavKey = param == navKeyParam
            ParameterSpec.builder(pName, pType).apply {
                if (isNavKey) {
                    // The user's @NavArg marker doesn't exist in Dagger's world. Emit Dagger's
                    // real @Assisted on the generated subclass so its @AssistedInject ctor is
                    // well-formed for Hilt's processor.
                    addAnnotation(AssistedAnnotation)
                }
                param.annotations.forEach { ann ->
                    // Drop our own @NavArg — it's a source-side marker only.
                    if (ann.shortName.asString() == "NavArg") return@forEach
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
            .addModifiers(visibility)
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

        val composableLambda = { params: Array<ParameterSpec> ->
            LambdaTypeName.get(parameters = params, returnType = UNIT).copy(
                annotations = listOf(AnnotationSpec.builder(ComposableAnnotation).build()),
            )
        }
        val contentWithKey = composableLambda(
            arrayOf(ParameterSpec.unnamed(vmTypeName), ParameterSpec.unnamed(keyTypeName))
        )
        val contentVmOnly = composableLambda(
            arrayOf(ParameterSpec.unnamed(vmTypeName))
        )
        val extraMetadataParam = ParameterSpec.builder("extraMetadata", MAP.parameterizedBy(STRING, ANY))
            .defaultValue("emptyMap()")
            .build()

        // Full overload: content receives (vm, navKey). This is the canonical one — does the
        // actual hiltViewModel<…>(creationCallback) work.
        val entryFnFull = FunSpec.builder(entryName)
            .addModifiers(visibility)
            .receiver(EntryProviderScope.parameterizedBy(NavKey))
            .addParameter(extraMetadataParam)
            .addParameter(ParameterSpec.builder("content", contentWithKey).build())
            // `entry` is a member of EntryProviderScope (this fn's receiver), so just call it.
            .addStatement(
                "entry<%T>(metadata = { extraMetadata }) { %L ->",
                keyTypeName, keyParamName,
            )
            .addStatement(
                "    content(%M<%T, %T>(creationCallback = { it.create(%L) }), %L)",
                HiltViewModelMember, hiltSubTypeName, factoryTypeName, keyParamName, keyParamName,
            )
            .addStatement("}")
            .build()

        // VM-only overload: content receives just (vm). Delegates to the (vm, key) overload via
        // a 2-arg lambda so the resolver picks the canonical one. Useful when the screen doesn't
        // need direct access to the nav key.
        val entryFnVmOnly = FunSpec.builder(entryName)
            .addModifiers(visibility)
            .receiver(EntryProviderScope.parameterizedBy(NavKey))
            .addParameter(extraMetadataParam)
            .addParameter(ParameterSpec.builder("content", contentVmOnly).build())
            .addStatement("$entryName(extraMetadata) { vm, _ -> content(vm) }")
            .build()

        val containingFile = vmClass.containingFile ?: run {
            logger.error("Cannot find containing file for $vmName", vmClass)
            return
        }

        FileSpec.builder(packageName, "${vmName}_Nav")
            .addType(hiltSubclass)
            .addFunction(entryFnFull)
            .addFunction(entryFnVmOnly)
            .build()
            .writeTo(
                codeGenerator,
                Dependencies(aggregating = false, containingFile),
            )
    }

    private companion object {
        val AssistedAnnotation = ClassName("dagger.assisted", "Assisted")
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

/** Maps the declared Kotlin visibility of a class to the equivalent KotlinPoet modifier. */
private fun KSClassDeclaration.visibilityModifier(): KModifier = when (getVisibility()) {
    Visibility.INTERNAL -> KModifier.INTERNAL
    Visibility.PROTECTED -> KModifier.PROTECTED
    Visibility.PRIVATE -> KModifier.PRIVATE
    else -> KModifier.PUBLIC
}
