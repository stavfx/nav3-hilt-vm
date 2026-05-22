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
package com.stavfx.nav3hiltvm.annotations

/**
 * Marks a `ViewModel` whose primary constructor takes a Navigation 3 `NavKey` (the route args)
 * alongside its Hilt-injected dependencies.
 *
 * The annotated class **must** declare a single primary constructor with exactly one parameter
 * annotated `@Assisted` (from `dagger.assisted.Assisted`), and that parameter's type must be a
 * subtype of `androidx.navigation3.runtime.NavKey`. The class itself must be `open` (or made so
 * via the `kotlin-allopen` plugin in the consuming module) — codegen produces a subclass that
 * wires Hilt + assisted injection.
 *
 * Apply on a plain VM class — do **not** also add `@HiltViewModel`, `@AssistedInject`, or an
 * `@AssistedFactory` interface. The KSP processor generates a sibling `<ClassName>Hilt` subclass
 * containing all the Hilt scaffolding, plus an `EntryProviderScope<NavKey>` extension that
 * resolves the subclass through `hiltViewModel<>(creationCallback = …)`.
 *
 * Example:
 * ```
 * @HiltNavKeyViewModel
 * open class MyScreenViewModel(
 *     private val store: MyStore,
 *     @Assisted private val navArgs: MyScreenNavArgs,
 * ) : ViewModel()
 * ```
 *
 * Generated:
 * ```
 * @HiltViewModel(assistedFactory = MyScreenViewModelHilt.Factory::class)
 * class MyScreenViewModelHilt @AssistedInject constructor(
 *     store: MyStore,
 *     @Assisted navArgs: MyScreenNavArgs,
 * ) : MyScreenViewModel(store, navArgs) {
 *     @AssistedFactory
 *     interface Factory {
 *         fun create(navArgs: MyScreenNavArgs): MyScreenViewModelHilt
 *     }
 * }
 *
 * fun EntryProviderScope<NavKey>.myScreenEntry(
 *     extraMetadata: Map<String, Any> = emptyMap(),
 *     content: @Composable (MyScreenViewModel) -> Unit,
 * ) = entry<MyScreenNavArgs>(metadata = { extraMetadata }) { navArgs ->
 *     content(hiltViewModel<MyScreenViewModelHilt, MyScreenViewModelHilt.Factory>(
 *         creationCallback = { it.create(navArgs) },
 *     ))
 * }
 * ```
 *
 * Call sites get the base type, the subclass instance:
 * ```
 * myScreenEntry { vm -> MyScreen(vm) }   // vm: MyScreenViewModel
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class HiltNavKeyViewModel
