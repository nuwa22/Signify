package com.logicroom.signify.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class SupportedWordsDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val mapping = mapOf(
            "COLOMBO"     to "කොළඹ",
            "COME"        to "එන්න",
            "DONT"        to "එපා",
            "DRINK"       to "බොන්න",
            "EAT"         to "කන්න",
            "FATHER"      to "තාත්තා",
            "GIVE"        to "දෙන්න",
            "GO"          to "යන්න",
            "GOODMORNING" to "සුබ උදෑසනක්",
            "GOODNIGHT"   to "සුබ රාත්‍රියක්",
            "HELLO"       to "ආයුබෝවන්",
            "HELP"        to "උදව්",
            "HOME"        to "ගෙදර",
            "HOSPITAL"    to "රෝහල",
            "ME"          to "මම",
            "MOTHER"      to "අම්මා",
            "POLICE"      to "පොලීසිය",
            "THANKS"      to "ස්තූතියි",
            "TIME"        to "වේලාව",
            "TODAY"       to "අද",
            "WHERE"       to "කොහේද",
            "WHO"         to "කවුද",
            "YES"         to "ඔව්",
            "YOU"         to "ඔබ"
        )

        val displayList = mapping.entries.map { "${it.key} → ${it.value}" }.toTypedArray()

        return AlertDialog.Builder(requireContext())
            .setTitle("හඳුනාගත හැකි සංඥා (24)")
            .setItems(displayList, null)
            .setPositiveButton("Close") { dialog, _ -> dialog.dismiss() }
            .create()
    }
}