package com.anchorreader.supertonic

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

enum class SupertonicQualityMode(val steps: Int, val title: String, val subtitle: String) {
    STUDIO_5_STEP(5, "Studio (5 steps)", "Full reference fidelity."),
    FAST_3_STEP(3, "Fast (3 steps)", "Faster synthesis with near-studio quality.");

    companion object {
        fun fromName(value: String?): SupertonicQualityMode =
            entries.firstOrNull { it.name == value } ?: STUDIO_5_STEP
    }
}

class SupertonicQualityStore(
    private val dataStore: DataStore<Preferences>,
    private val testScope: CoroutineScope? = null
) {
    private val key = stringPreferencesKey("supertonic_quality_mode")

    val qualityMode: Flow<SupertonicQualityMode> = dataStore.data.map { preferences ->
        SupertonicQualityMode.fromName(preferences[key])
    }

    suspend fun setQualityMode(mode: SupertonicQualityMode) {
        dataStore.edit { preferences ->
            preferences[key] = mode.name
        }
    }

    fun closeForTest() {
        testScope?.cancel()
    }

    companion object {
        @Volatile
        private var instance: SupertonicQualityStore? = null

        fun getInstance(context: Context): SupertonicQualityStore {
            return instance ?: synchronized(this) {
                instance ?: SupertonicQualityStore(
                    PreferenceDataStoreFactory.create(
                        produceFile = { context.applicationContext.preferencesDataStoreFile("supertonic_quality_prefs") }
                    )
                ).also { instance = it }
            }
        }

        fun createForTest(
            file: File,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        ): SupertonicQualityStore {
            return SupertonicQualityStore(
                PreferenceDataStoreFactory.create(
                    scope = scope,
                    produceFile = { file }
                ),
                testScope = scope
            )
        }
    }
}
