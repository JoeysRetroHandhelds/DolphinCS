// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.dialogs

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.dolphinemu.dolphinemu.R
import org.dolphinemu.dolphinemu.ui.main.MainActivity
import org.dolphinemu.dolphinemu.utils.Analytics

class AnalyticsDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(requireContext().getString(R.string.analytics))
            .setMessage(requireContext().getString(R.string.analytics_desc))
            .setPositiveButton(R.string.yes) { _, _ ->
                Analytics.firstAnalyticsAdd(true)
            }
            .setNegativeButton(R.string.no) { _, _ ->
                Analytics.firstAnalyticsAdd(false)
            }
        return dialog.create()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // The migration prompt is held back while this dialog is up — check again now instead
        // of waiting for the next onResume.
        (activity as? MainActivity)?.checkMigration()
    }

    companion object {
        const val TAG = "AnalyticsDialog"
    }
}
