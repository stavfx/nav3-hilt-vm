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

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier

/**
 * KSP processor for `@HiltNavKeyViewModel`.
 *
 * For each annotated class, emits a single `<ClassName>_Nav.kt` containing:
 *  - `<ClassName>Hilt` — a subclass annotated `@HiltViewModel(assistedFactory = …)` with an
 *    `@AssistedInject` constructor that mirrors the user's primary ctor and forwards via
 *    `super(...)`. Contains the nested `Factory` interface.
 *  - `<className>Entry` — an `EntryProviderScope<NavKey>` extension that resolves the subclass
 *    through `hiltViewModel<…>(creationCallback = …)` and hands it to the user's content lambda
 *    as the **base** type.
 *
 * Because the `@HiltViewModel(assistedFactory = X::class)` annotation and `X` (the Factory) are
 * both in the same generated file, Hilt's processor sees a fully-resolved reference on round 1.
 * No forward-reference / round-ordering issue.
 */
class HiltNavViewModelProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private lateinit var navKeyType: KSType

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val navKeyDeclaration = resolver
            .getClassDeclarationByName(
                resolver.getKSNameFromString("androidx.navigation3.runtime.NavKey")
            )
        if (navKeyDeclaration == null) {
            // Defer to the next round — likely the consumer module's classpath hasn't surfaced
            // androidx.navigation3 yet. (In tests we stub it, so this branch is rarely hit.)
            return emptyList()
        }
        navKeyType = navKeyDeclaration.asStarProjectedType()

        resolver.getSymbolsWithAnnotation("com.stavfx.nav3hiltvm.annotations.HiltNavKeyViewModel")
            .filterIsInstance<KSClassDeclaration>()
            .forEach(::process)
        return emptyList()
    }

    private fun process(vmClass: KSClassDeclaration) {
        // The user's class must be extensible since we emit a subclass that wires Hilt + assisted
        // injection. Without this, the user gets a Kotlin "this type is final, so it cannot be
        // inherited from" error pointing at the generated file — confusing because the cause is
        // in the user's source. Surface a clear message ourselves first.
        if (Modifier.OPEN !in vmClass.modifiers && Modifier.ABSTRACT !in vmClass.modifiers) {
            logger.error(
                "@HiltNavKeyViewModel class must be declared `open` (or `abstract`) so the " +
                    "generated Hilt subclass can extend it",
                vmClass,
            )
            return
        }

        val primaryCtor = vmClass.primaryConstructor ?: run {
            logger.error(
                "@HiltNavKeyViewModel requires a primary constructor",
                vmClass,
            )
            return
        }

        // We only allow a single constructor — secondary ctors would create ambiguity about which
        // signature to mirror in the generated Hilt subclass.
        val allCtors = vmClass.getConstructors().toList()
        if (allCtors.size > 1) {
            logger.error(
                "@HiltNavKeyViewModel classes must declare exactly one constructor; " +
                    "found ${allCtors.size}",
                vmClass,
            )
            return
        }

        val navArgParams = primaryCtor.parameters.filter { it.isNavArg() }
        if (navArgParams.size != 1) {
            logger.error(
                "@HiltNavKeyViewModel requires exactly one @NavArg parameter; " +
                    "found ${navArgParams.size}",
                vmClass,
            )
            return
        }
        val navKeyParam = navArgParams.single()
        val navKeyParamType = navKeyParam.type.resolve()
        if (!navKeyType.isAssignableFrom(navKeyParamType)) {
            logger.error(
                "@HiltNavKeyViewModel's @NavArg parameter must be a subtype of " +
                    "androidx.navigation3.runtime.NavKey; got " +
                    (navKeyParamType.declaration.qualifiedName?.asString() ?: "<unknown>"),
                navKeyParam,
            )
            return
        }

        FactoryGenerator(codeGenerator, logger).generate(vmClass, primaryCtor, navKeyParam)
    }
}

private fun KSValueParameter.isNavArg(): Boolean =
    annotations.any { it.shortName.asString() == "NavArg" }
