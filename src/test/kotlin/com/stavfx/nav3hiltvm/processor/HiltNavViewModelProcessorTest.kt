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

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for [HiltNavViewModelProcessor].
 *
 * Each test compiles a synthetic Kotlin source with our processor wired in (via KSP2 — kctfork
 * 0.7.1 bundles an older KSP1 that silently no-ops on processors built against newer ksp-api,
 * see #54 history), then asserts on the generated `<VMName>_Nav.kt` text.
 *
 * Annotation/type stubs ([STUB_SOURCES]) stand in for AndroidX + Hilt so tests don't need those
 * artifacts on the classpath — the processor only reads annotation shapes and type names, not
 * implementations. Downstream Kotlin compilation of the generated file may fail because the real
 * Compose/Nav3 types aren't available; that's irrelevant — we assert on the generated text.
 */
@OptIn(ExperimentalCompilerApi::class)
class HiltNavViewModelProcessorTest {

    @Test
    fun `happy path generates Hilt subclass with assisted ctor and entry helper`() {
        val vm = SourceFile.kotlin(
            "MyScreenViewModel.kt",
            """
            package com.example

            import androidx.navigation3.runtime.NavKey
            import com.stavfx.nav3hiltvm.annotations.NavArg
            import com.stavfx.nav3hiltvm.annotations.HiltNavArgViewModel

            data class MyScreenNavArgs(val id: String) : NavKey

            interface MyStore

            @HiltNavArgViewModel
            open class MyScreenViewModel(
                private val store: MyStore,
                @NavArg private val navArgs: MyScreenNavArgs,
            )
            """.trimIndent()
        )

        val result = compile(vm)
        val generated = result.findGenerated("MyScreenViewModel_Nav.kt")

        // The Hilt subclass — annotation, super call, factory, all in the same generated file.
        assertContains(
            generated,
            "@HiltViewModel(assistedFactory = MyScreenViewModel_HiltNavArgs.Factory::class)",
        )
        assertContains(generated, "class MyScreenViewModel_HiltNavArgs @AssistedInject constructor(")
        assertContains(generated, "store: MyStore,")
        // The @Assisted param: annotation lands on its own indented line above the param name.
        assertContains(generated, "@Assisted")
        assertContains(generated, "navArgs: MyScreenNavArgs,")
        assertContains(generated, ") : MyScreenViewModel(store, navArgs)")
        assertContains(generated, "@AssistedFactory")
        assertContains(generated, "public interface Factory {")
        assertContains(
            generated,
            "public fun create(navArgs: MyScreenNavArgs): MyScreenViewModel_HiltNavArgs",
        )

        // The entry helper exposes the BASE type to the content lambda, but resolves the SUBCLASS
        // through hiltViewModel.
        assertContains(generated, "EntryProviderScope<NavKey>.myScreenEntry")
        // Full overload — content receives (vm, navKey).
        assertContains(generated, "content: @Composable (MyScreenViewModel, MyScreenNavArgs) -> Unit")
        assertContains(generated, "entry<MyScreenNavArgs>(metadata = { extraMetadata })")
        assertContains(generated, "hiltViewModel<MyScreenViewModel_HiltNavArgs, MyScreenViewModel_HiltNavArgs.Factory>")
        assertContains(generated, "creationCallback = { it.create(navArgs) }), navArgs)")

        // VM-only overload — content receives just (vm); delegates via 2-arg lambda.
        assertContains(generated, "content: @Composable (MyScreenViewModel) -> Unit")
        assertContains(generated, "myScreenEntry(extraMetadata) { vm, _ -> content(vm) }")
    }

    @Test
    fun `generated subclass and entries mirror an internal VM's visibility`() {
        val vm = SourceFile.kotlin(
            "SecretViewModel.kt",
            """
            package com.example

            import androidx.navigation3.runtime.NavKey
            import com.stavfx.nav3hiltvm.annotations.NavArg
            import com.stavfx.nav3hiltvm.annotations.HiltNavArgViewModel

            data class SecretNavArgs(val id: String) : NavKey

            @HiltNavArgViewModel
            internal open class SecretViewModel(
                @NavArg private val navArgs: SecretNavArgs,
            )
            """.trimIndent()
        )
        val result = compile(vm)
        val generated = result.findGenerated("SecretViewModel_Nav.kt")
        assertContains(generated, "internal class SecretViewModel_HiltNavArgs")
        assertContains(generated, "internal fun EntryProviderScope<NavKey>.secretEntry")
    }

    @Test
    fun `entry name strips ViewModel suffix and lowercases first letter`() {
        // MyViewModel → myEntry
        val my = vm("MyViewModel", keyClass = "MyNavArgs", keyParamName = "args")
        val result = compile(my)
        val generated = result.findGenerated("MyViewModel_Nav.kt")
        assertContains(generated, ".myEntry(")
    }

    @Test
    fun `non-conventional class names without ViewModel suffix still get Entry appended`() {
        val player = vm("PlayerScreen", keyClass = "PlayerArgs", keyParamName = "args")
        val result = compile(player)
        val generated = result.findGenerated("PlayerScreen_Nav.kt")
        assertContains(generated, ".playerScreenEntry(")
    }

    @Test
    fun `parameter annotations carry through to the generated subclass`() {
        // Hilt qualifiers (@Named etc.) on the user's ctor params must reach the generated
        // subclass's ctor or Hilt can't resolve them. We use a custom @Named-like marker here
        // to avoid pulling in javax.inject.
        val vm = SourceFile.kotlin(
            "QualifiedViewModel.kt",
            """
            package com.example

            import androidx.navigation3.runtime.NavKey
            import com.stavfx.nav3hiltvm.annotations.NavArg
            import com.stavfx.nav3hiltvm.annotations.HiltNavArgViewModel

            data class QualifiedNavArgs(val id: String) : NavKey

            @Target(AnnotationTarget.VALUE_PARAMETER)
            annotation class MyQualifier

            interface SomeDep

            @HiltNavArgViewModel
            open class QualifiedViewModel(
                @MyQualifier private val dep: SomeDep,
                @NavArg private val args: QualifiedNavArgs,
            )
            """.trimIndent()
        )
        val result = compile(vm)
        val generated = result.findGenerated("QualifiedViewModel_Nav.kt")
        assertContains(generated, "@MyQualifier\n  dep: SomeDep,")
    }

    @Test
    fun `errors when @NavArg param is not a NavKey subtype`() {
        val bad = SourceFile.kotlin(
            "NonNavKeyViewModel.kt",
            """
            package com.example

            import com.stavfx.nav3hiltvm.annotations.NavArg
            import com.stavfx.nav3hiltvm.annotations.HiltNavArgViewModel

            @HiltNavArgViewModel
            open class NonNavKeyViewModel(
                @NavArg private val notAKey: String,
            )
            """.trimIndent()
        )
        val result = compile(bad)
        assertCompilationError(result, "must be a subtype of androidx.navigation3.runtime.NavKey")
    }

    @Test
    fun `errors when zero @NavArg parameters`() {
        val bad = SourceFile.kotlin(
            "ZeroAssistedViewModel.kt",
            """
            package com.example

            import androidx.navigation3.runtime.NavKey
            import com.stavfx.nav3hiltvm.annotations.HiltNavArgViewModel

            interface Dep

            @HiltNavArgViewModel
            open class ZeroAssistedViewModel(private val dep: Dep)
            """.trimIndent()
        )
        val result = compile(bad)
        assertCompilationError(result, "requires exactly one @NavArg parameter; found 0")
    }

    @Test
    fun `errors when multiple @NavArg parameters`() {
        val bad = SourceFile.kotlin(
            "TwoAssistedViewModel.kt",
            """
            package com.example

            import androidx.navigation3.runtime.NavKey
            import com.stavfx.nav3hiltvm.annotations.NavArg
            import com.stavfx.nav3hiltvm.annotations.HiltNavArgViewModel

            data class ArgsA(val id: String) : NavKey
            data class ArgsB(val id: String) : NavKey

            @HiltNavArgViewModel
            open class TwoAssistedViewModel(
                @NavArg private val a: ArgsA,
                @NavArg private val b: ArgsB,
            )
            """.trimIndent()
        )
        val result = compile(bad)
        assertCompilationError(result, "requires exactly one @NavArg parameter; found 2")
    }

    @Test
    fun `errors with a clear message when the annotated class is not open`() {
        // Without an explicit `open class`, our generated subclass can't extend it. The fallback
        // would be a Kotlin "this type is final" error pointing at the generated file — confusing
        // because the actual fix is in the user's source. The processor catches it up front.
        val bad = SourceFile.kotlin(
            "FinalViewModel.kt",
            """
            package com.example

            import androidx.navigation3.runtime.NavKey
            import com.stavfx.nav3hiltvm.annotations.NavArg
            import com.stavfx.nav3hiltvm.annotations.HiltNavArgViewModel

            data class FakeArgs(val id: String) : NavKey

            @HiltNavArgViewModel
            class FinalViewModel(
                @NavArg private val args: FakeArgs,
            )
            """.trimIndent()
        )
        val result = compile(bad)
        assertCompilationError(
            result,
            "class must be declared `open` (or `abstract`)",
        )
    }

    @Test
    fun `errors when class declares multiple constructors`() {
        val bad = SourceFile.kotlin(
            "MultiCtorViewModel.kt",
            """
            package com.example

            import androidx.navigation3.runtime.NavKey
            import com.stavfx.nav3hiltvm.annotations.NavArg
            import com.stavfx.nav3hiltvm.annotations.HiltNavArgViewModel

            data class FakeArgs(val id: String) : NavKey

            @HiltNavArgViewModel
            open class MultiCtorViewModel(
                @NavArg private val args: FakeArgs,
            ) {
                constructor(other: String, args: FakeArgs) : this(args)
            }
            """.trimIndent()
        )
        val result = compile(bad)
        assertCompilationError(result, "must declare exactly one constructor")
    }

    /** Assert that a compilation both failed AND surfaced the expected processor error. */
    private fun assertCompilationError(result: JvmCompilationResult, expectedMessage: String) {
        assertEquals(
            KotlinCompilation.ExitCode.COMPILATION_ERROR,
            result.exitCode,
            "Expected compilation to fail with COMPILATION_ERROR. Messages:\n${result.messages}",
        )
        assertContains(result.messages, expectedMessage)
    }

    /**
     * Minimal VM template for naming-convention tests: one @Assisted NavKey param + one Hilt dep.
     */
    private fun vm(className: String, keyClass: String, keyParamName: String): SourceFile =
        SourceFile.kotlin(
            "$className.kt",
            """
            package com.example

            import androidx.navigation3.runtime.NavKey
            import com.stavfx.nav3hiltvm.annotations.NavArg
            import com.stavfx.nav3hiltvm.annotations.HiltNavArgViewModel

            data class $keyClass(val id: String) : NavKey

            interface SomeDep

            @HiltNavArgViewModel
            open class $className(
                private val dep: SomeDep,
                @NavArg private val $keyParamName: $keyClass,
            )
            """.trimIndent()
        )

    private fun compile(vararg sources: SourceFile): JvmCompilationResult =
        KotlinCompilation().apply {
            this.sources = STUB_SOURCES + sources.toList()
            configureKsp(useKsp2 = true) {
                symbolProcessorProviders += HiltNavViewModelProcessorProvider()
            }
            inheritClassPath = true
            messageOutputStream = System.out
        }.compile()

    private fun JvmCompilationResult.findGenerated(name: String): String {
        // outputDirectory is <workingDir>/classes; KSP writes to <workingDir>/ksp/sources/kotlin/.
        // Walk from <workingDir> (not the system temp root, or we'd match files left behind by
        // prior compilations) so each test asserts only on its own output.
        val workingDir = outputDirectory.parentFile
        val file = workingDir.walkTopDown().firstOrNull { it.name == name }
        assertNotNull(file) {
            "Expected generated file $name; available files under $workingDir:\n" +
                workingDir.walkTopDown().filter { it.isFile }.joinToString("\n")
        }
        return file.readText()
    }

    companion object {
        private val STUB_SOURCES = listOf(
            SourceFile.kotlin(
                "Nav3Stubs.kt",
                """
                package androidx.navigation3.runtime
                interface NavKey
                """.trimIndent()
            ),
            SourceFile.kotlin(
                "DaggerStubs.kt",
                """
                package dagger.assisted

                @Target(AnnotationTarget.CONSTRUCTOR) annotation class AssistedInject
                @Target(AnnotationTarget.VALUE_PARAMETER) annotation class Assisted
                @Target(AnnotationTarget.CLASS) annotation class AssistedFactory
                """.trimIndent()
            ),
            SourceFile.kotlin(
                "HiltStubs.kt",
                """
                package dagger.hilt.android.lifecycle
                import kotlin.reflect.KClass

                @Target(AnnotationTarget.CLASS)
                annotation class HiltViewModel(val assistedFactory: KClass<*> = Any::class)
                """.trimIndent()
            ),
        )
    }
}
