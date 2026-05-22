# nav3-hilt-vm

KSP processor that takes the boilerplate out of Hilt + Navigation 3 ViewModels with assisted NavKey injection.

The [official Hilt + Navigation 3 + assisted-injection recipe][recipe] requires four moving parts per screen: a `@HiltViewModel(assistedFactory = …)` annotation, an `@AssistedInject` constructor, a nested `@AssistedFactory` interface, and a verbose `hiltViewModel<VM, Factory>(creationCallback = …)` block in your entry provider. This library reduces all of that to a single `@HiltNavKeyViewModel` annotation on a plain VM class.

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

Apply `@HiltNavKeyViewModel` to a plain `open` class, drop the rest:

```kotlin
@HiltNavKeyViewModel
open class MyScreenViewModel(
    private val store: MyStore,
    @Assisted private val navArgs: MyScreenNavArgs,
) : ViewModel()
```

```kotlin
NavDisplay(
    backStack = backStack,
    entryProvider = entryProvider {
        myScreenEntry { MyScreen(it) }
        // …one line like this per screen
    },
)
```

## Setup

TBD

## Requirements

The annotated class must:

1. Be declared `open` (or `abstract`) — the processor generates a subclass that extends it.
2. Have a single primary constructor.
3. Have exactly one parameter annotated `@Assisted` whose type implements `androidx.navigation3.runtime.NavKey`.

All other constructor parameters are treated as Hilt-injected dependencies. Parameter annotations (`@ApplicationContext`, `@Named`, custom qualifiers) pass through to the generated subclass so Hilt resolves them correctly.

## What gets generated

For an annotated `MyScreenViewModel`, the processor emits a sibling file `MyScreenViewModel_Nav.kt` containing:

- **`MyScreenViewModelHilt`** — a subclass annotated `@HiltViewModel(assistedFactory = MyScreenViewModelHilt.Factory::class)` with an `@AssistedInject` constructor that mirrors yours and forwards via `super(...)`. The nested `@AssistedFactory interface Factory` lives here.
- **`myScreenEntry`** — an `EntryProviderScope<NavKey>` extension that resolves the subclass through `hiltViewModel<…>(creationCallback = …)` and hands the result to your `content` lambda **as the base type** (`MyScreenViewModel`). Call sites stay agnostic of the generated subclass.

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
