package com.stavfx.nav3hiltvm.processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

class HiltNavViewModelProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        HiltNavViewModelProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
        )
}
