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
 * Marks the constructor parameter that carries the route's `NavKey` — the runtime value provided
 * by the Navigation 3 entry rather than by Hilt's DI graph.
 *
 * Use exactly once per `@HiltNavKeyViewModel` class, on a parameter whose type implements
 * `androidx.navigation3.runtime.NavKey`. All other constructor parameters are treated as
 * Hilt-injected.
 *
 * Why not `dagger.assisted.Assisted` directly? Dagger's KSP processor errors out when it sees
 * `@Assisted` on a constructor that isn't itself `@AssistedInject`. The user's class is plain;
 * codegen emits a `@AssistedInject` constructor on the generated Hilt subclass and transcribes
 * this marker into `@Assisted` there.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class NavArg
