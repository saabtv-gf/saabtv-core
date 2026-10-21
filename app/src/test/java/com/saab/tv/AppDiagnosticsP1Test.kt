package com.saab.tv

import android.app.Application
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import com.saab.tv.testing.awaitAppState

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class AppDiagnosticsP1Test {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun setup() {AppDiagnostics.clear(context);AppDiagnostics.setBasicEnabled(context,false)}
    @After fun cleanup() {AppDiagnostics.clear(context)}
    @Test fun basicLoggingDefaultsOffAndRoutineEventsAreNotPersisted() {
        assertFalse(AppDiagnostics.isBasicEnabled(context))
        AppDiagnostics.event(context,"Fixture","Routine","ignored")
        assertFalse(AppDiagnostics.report(context).events.any {it.event=="Routine"})
    }
    @Test fun uncaughtExceptionIsPersistedEvenWhenBasicLoggingOff() {
        AppDiagnostics.failure(context,"Application","Uncaught Exception",IllegalStateException("fixture"))
        assertTrue(AppDiagnostics.report(context).events.any {it.event=="Uncaught Exception" && it.level=="FATAL"})
    }
    @Test fun optInRoutineEventsPersistAndExportRedactsSecrets() {
        AppDiagnostics.setBasicEnabled(context,true)
        AppDiagnostics.event(context,"Fixture","Saved","Authorization: Bearer supersecretfixture")
        awaitAppState {AppDiagnostics.report(context).events.any {it.event=="Saved"}}
        val export=AppDiagnostics.export(context)!!
        assertTrue(export.readText().contains("Saved"));assertFalse(export.readText().contains("supersecretfixture"))
    }
    @Test fun exportedEventUsesOneComponentColumnAndPreservesEventName() {
        AppDiagnostics.setBasicEnabled(context, true)
        AppDiagnostics.event(context, "Updater", "Download Started", "attempt=1")
        awaitAppState { AppDiagnostics.report(context).events.any { it.event == "Download Started" } }

        val exportText = AppDiagnostics.export(context)!!.readText()
        val eventLine = exportText.lineSequence().first { it.contains("Download Started") }
        assertTrue(eventLine.contains("Updater Download Started"))
        assertFalse(eventLine.contains("Updater Updater"))
    }
    @Test fun cancellationIsNotRecordedAsFailure() {
        AppDiagnostics.setBasicEnabled(context,true)
        AppDiagnostics.failure(context,"Fixture","Cancelled",kotlinx.coroutines.CancellationException("normal"))
        assertFalse(AppDiagnostics.report(context).events.any {it.event=="Cancelled"})
    }
    @Test fun reportLimitAndClearAreBoundedAndDeterministic() {
        repeat(10) {AppDiagnostics.failure(context,"Application","Uncaught Exception",IllegalStateException("$it"))}
        assertEquals(3,AppDiagnostics.report(context,3).events.size)
        AppDiagnostics.clear(context)
        assertFalse(AppDiagnostics.report(context).events.any {it.event=="Uncaught Exception"})
    }

    @Test fun oversizedEventLabelsAreBoundedBeforePersistenceAndExport() {
        AppDiagnostics.setBasicEnabled(context,true)
        AppDiagnostics.event(context, "c".repeat(1_000), "e".repeat(1_000), "bounded")

        awaitAppState { AppDiagnostics.report(context).events.any { it.details == "bounded" } }
        val item = AppDiagnostics.report(context).events.first { it.details == "bounded" }
        assertTrue(item.component.length <= 120)
        assertTrue(item.event.length <= 160)
        assertTrue(AppDiagnostics.export(context)!!.length() <= DiagnosticStorageBudget.MAX_EXPORT_BYTES)
    }
}
