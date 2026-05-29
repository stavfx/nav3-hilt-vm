# nav3-hilt-vm

KSP processor that wires Hilt ViewModels into Navigation 3 entries with two annotations and one line per screen.

The [official Hilt + Navigation 3 + assisted-injection recipe][recipe] requires four moving parts per screen: a `@HiltViewModel(assistedFactory = …)` annotation, an `@AssistedInject` constructor, a nested `@AssistedFactory` interface, and a verbose `hiltViewModel<VM, Factory>(creationCallback = …)` block in your entry provider.  
This library replaces all of that with `@HiltNavArgViewModel` on a plain VM class plus `@NavArg` on the route parameter.

> **Why "NavArg"?** The terminology is deliberately "NavArg" rather than "NavKey" to avoid ambiguity with Jetpack Navigation 3's own `androidx.navigation3.runtime.NavKey` type — the marked parameter's value still implements that type.

## Before

```kotlin
@HiltViewModel(assistedFactory = MyScreenViewModel.Factory::class)
class MyScreenViewModel @AssistedInject constructor(
    private val store: MyStore,
    @Assisted private val navArgs: MyScreenNavArgs,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(navArgs: MyScreenNavArgs): MyScreenViewModel
    }
}
```

```kotlin
NavDisplay(
    backStack = backStack,
    entryProvider = entryProvider {
        entry<MyScreenNavArgs> { key ->
            val vm = hiltViewModel<MyScreenViewModel, MyScreenViewModel.Factory>(
                creationCallback = { it.create(key) },
            )
            MyScreen(vm)
        }
        // …one block like this per screen
    },
)
```

## After

Apply `@HiltNavArgViewModel` to a plain `open` class, mark the route param with `@NavArg`, drop the rest:

```kotlin
@HiltNavArgViewModel
open class MyScreenViewModel(
    private val store: MyStore,
    @NavArg private val navArgs: MyScreenNavArgs,
) : ViewModel()
```

```kotlin
NavDisplay(
    backStack = backStack,
    entryProvider = entryProvider {
        myScreenEntry { vm -> MyScreen(vm) }
        // …one line like this per screen
    },
)
```

Two overloads are generated per screen — pick whichever you need:

```kotlin
myScreenEntry { vm -> MyScreen(vm) }                  // vm only
myScreenEntry { vm, navArgs -> MyScreen(vm, navArgs) } // vm + the nav key, if you need it
```

## Setup

```kotlin
// build.gradle.kts (consumer module)
plugins {
    id("com.google.devtools.ksp")  // already present if you use Hilt with KSP
}

dependencies {
    implementation("com.stavfx:nav3-hilt-vm:0.3.0")
    ksp("com.stavfx:nav3-hilt-vm:0.3.0")
}
```

`implementation` exposes the `@HiltNavArgViewModel` / `@NavArg` annotations; `ksp` runs the processor at build time. Same artifact for both.

The library assumes your consumer module already has the standard Hilt + Navigation 3 + Compose stack — it just generates the glue between them.

## Requirements

The annotated class must:

1. Be declared `open` (or `abstract`) — the processor generates a subclass that extends it.
2. Have a single primary constructor.
3. Have exactly one parameter annotated `@NavArg` whose type implements `androidx.navigation3.runtime.NavKey`. (`@NavArg` is our own marker — Dagger's `@Assisted` would error out on a plain class. Codegen transcribes `@NavArg` into a real `@Assisted` on the generated subclass.)

All other constructor parameters are treated as Hilt-injected dependencies. Parameter annotations (`@ApplicationContext`, `@Named`, custom qualifiers) pass through to the generated subclass so Hilt resolves them correctly.

## What gets generated

For an annotated `MyScreenViewModel`, the processor emits a sibling file `MyScreenViewModel_Nav.kt` containing:

- **`MyScreenViewModel_HiltNavArgs`** — a subclass annotated `@HiltViewModel(assistedFactory = …)` with an `@AssistedInject` constructor that mirrors yours and forwards via `super(...)`. The nested `Factory` interface lives here. (Hilt's `@AssistedInject` and `@Assisted` only appear in this generated file — you never write them yourself.)

The generated subclass and entry functions mirror the visibility of your ViewModel — mark it `internal` and the generated code is `internal` too.
- **`myScreenEntry`** — two `EntryProviderScope<NavKey>` overloads (one with `content: (vm) -> Unit`, one with `content: (vm, navKey) -> Unit`). Both resolve the subclass through `hiltViewModel` and hand it to your content lambda as the **base type** (`MyScreenViewModel`). Call sites stay agnostic of the generated subclass.

## Compatibility

Built and tested against:

- Kotlin **2.2.10**
- KSP **2.2.10-2.0.2** (use KSP2)
- KotlinPoet **2.2.0**
- Hilt **2.49+** (first version with `@HiltViewModel(assistedFactory = …)`)
- Navigation 3 **1.0+**

## Status

Early. API may evolve. Open to issues and PRs.

[recipe]: https://github.com/android/nav3-recipes/tree/main/app/src/main/java/com/example/nav3recipes/passingarguments/viewmodels/hilt
