# nav3-hilt-codegen

KSP processor that takes the boilerplate out of `Hilt + Navigation 3 + @Assisted` ViewModels.

## Before

The [official Hilt + Nav 3 + assisted-injection pattern](https://github.com/android/nav3-recipes/tree/main/app/src/main/java/com/example/nav3recipes/passingarguments/viewmodels/hilt):

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

Apply `@HiltNavKeyViewModel`, drop the rest:

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
