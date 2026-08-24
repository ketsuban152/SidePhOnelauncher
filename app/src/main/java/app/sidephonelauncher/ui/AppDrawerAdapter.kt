package app.sidephonelauncher.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.content.res.ColorStateList
import android.graphics.Paint
import android.os.UserHandle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Filter
import android.widget.Filterable
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.sidephonelauncher.R
import app.sidephonelauncher.data.AppModel
import app.sidephonelauncher.data.Constants
import app.sidephonelauncher.databinding.AdapterAppDrawerBinding
import app.sidephonelauncher.databinding.AdapterAppDrawerSpacerBinding
import app.sidephonelauncher.databinding.AdapterPrivateSpaceHeaderBinding
import app.sidephonelauncher.helper.getColorFromAttr
import app.sidephonelauncher.helper.hideKeyboard
import app.sidephonelauncher.helper.isSystemApp
import app.sidephonelauncher.helper.showKeyboard
import java.text.Normalizer

class AppDrawerAdapter(
    private var flag: Int,
    private val appLabelGravity: Int,
    private val focusIndicatorStyle: Int,
    private val appClickListener: (AppModel) -> Unit,
    private val appInfoListener: (AppModel) -> Unit,
    private val appDeleteListener: (AppModel) -> Unit,
    private val appHideListener: (AppModel, Int) -> Unit,
    private val appRenameListener: (AppModel, String) -> Unit,
    private val appLeftListener: () -> Boolean = { false },
    private val appBackListener: () -> Boolean = { false },
    private val appVerticalFocusListener: (Int, Int) -> Boolean = { _, _ -> false },
    private val privateSpaceToggleListener: () -> Unit = {},
    private val privateSpaceSettingsListener: () -> Unit = {},
) : ListAdapter<AppModel, RecyclerView.ViewHolder>(DIFF_CALLBACK), Filterable {

    companion object {
        const val VIEW_TYPE_APP = 0
        const val VIEW_TYPE_PRIVATE_HEADER = 1
        const val VIEW_TYPE_SPACER = 2

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppModel>() {
            override fun areItemsTheSame(oldItem: AppModel, newItem: AppModel): Boolean = when {
                oldItem is AppModel.App && newItem is AppModel.App ->
                    oldItem.appPackage == newItem.appPackage && oldItem.user == newItem.user

                oldItem is AppModel.PinnedShortcut && newItem is AppModel.PinnedShortcut ->
                    oldItem.shortcutId == newItem.shortcutId && oldItem.user == newItem.user

                oldItem is AppModel.PrivateSpaceHeader && newItem is AppModel.PrivateSpaceHeader -> true
                oldItem is AppModel.Spacer && newItem is AppModel.Spacer -> true

                else -> false
            }

            override fun areContentsTheSame(oldItem: AppModel, newItem: AppModel): Boolean =
                oldItem == newItem
        }
    }

    private var autoLaunch = true
    private var isBangSearch = false
    var allowAutoLaunch = true
    private val diacriticsRegex = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val separatorsRegex = Regex("[-_+,.`'\\s\\p{Z}]")
    private val appFilter = createAppFilter()
    private val myUserHandle = android.os.Process.myUserHandle()

    var appsList: MutableList<AppModel> = mutableListOf()
    var appFilteredList: MutableList<AppModel> = mutableListOf()

    override fun getItemViewType(position: Int): Int {
        return when (appFilteredList.getOrNull(position)) {
            is AppModel.PrivateSpaceHeader -> VIEW_TYPE_PRIVATE_HEADER
            is AppModel.Spacer -> VIEW_TYPE_SPACER
            else -> VIEW_TYPE_APP
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_PRIVATE_HEADER -> PrivateSpaceHeaderViewHolder(
                AdapterPrivateSpaceHeaderBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            VIEW_TYPE_SPACER -> SpacerViewHolder(
                AdapterAppDrawerSpacerBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> ViewHolder(
                AdapterAppDrawerBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        try {
            if (appFilteredList.isEmpty() || position == RecyclerView.NO_POSITION) return
            val appModel = appFilteredList[holder.bindingAdapterPosition]
            when (holder) {
                is PrivateSpaceHeaderViewHolder -> {
                    holder.bind(
                        appLabelGravity,
                        privateSpaceToggleListener,
                        privateSpaceSettingsListener,
                    )
                }

                is ViewHolder -> holder.bind(
                    flag,
                    appLabelGravity,
                    focusIndicatorStyle,
                    myUserHandle,
                    appModel,
                    appClickListener,
                    appDeleteListener,
                    appInfoListener,
                    appHideListener,
                    appRenameListener,
                    appLeftListener,
                    appBackListener,
                    appVerticalFocusListener,
                )

                is SpacerViewHolder -> Unit
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getFilter(): Filter = this.appFilter

    private fun createAppFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(charSearch: CharSequence?): FilterResults {
                isBangSearch = charSearch?.startsWith("!") ?: false
                autoLaunch = allowAutoLaunch && (charSearch?.startsWith(" ")?.not() ?: true)

                val appFilteredList = if (charSearch.isNullOrBlank()) {
                    appsList.toMutableList()
                } else {
                    appsList.filter { app ->
                        app !is AppModel.PrivateSpaceHeader
                                && app !is AppModel.Spacer
                                && appLabelMatches(app.appLabel, charSearch)
                    }.toMutableList()
                }.appendSpacerItem()

                val filterResults = FilterResults()
                filterResults.values = appFilteredList
                return filterResults
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                results?.values?.let {
                    val items = it as MutableList<AppModel>
                    appFilteredList = items
                    submitList(appFilteredList) {
                        autoLaunch()
                    }
                }
            }
        }
    }

    private fun autoLaunch() {
        try {
            val launchableResults = appFilteredList.filter {
                it !is AppModel.PrivateSpaceHeader
                        && it !is AppModel.Spacer
                        && it.appPackage.isNotBlank()
            }
            if (launchableResults.size == 1
                && autoLaunch
                && isBangSearch.not()
                && flag == Constants.FLAG_LAUNCH_APP
            ) {
                appClickListener(launchableResults[0])
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun appLabelMatches(appLabel: String, charSearch: CharSequence): Boolean {
        if (appLabel.contains(charSearch.trim(), true)) return true
        val query = charSearch.normalizeForSearch()
        return query.isNotEmpty() && appLabel.normalizeForSearch().contains(query, true)
    }

    private fun CharSequence.normalizeForSearch(): String =
        Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(diacriticsRegex, "")
            .replace(separatorsRegex, "")

    fun setAppList(appsList: MutableList<AppModel>) {
        val listWithSpacer = appsList.toMutableList().appendSpacerItem()
        this.appsList = listWithSpacer
        this.appFilteredList = listWithSpacer
        submitList(listWithSpacer)
    }

    fun hasLaunchableResults(): Boolean {
        return appFilteredList.any { it !is AppModel.PrivateSpaceHeader && it.appPackage.isNotBlank() }
    }

    fun launchFirstInList() {
        val first = appFilteredList.firstOrNull {
            it !is AppModel.PrivateSpaceHeader && it.appPackage.isNotBlank()
        }
        if (first != null) appClickListener(first)
    }

    private fun MutableList<AppModel>.appendSpacerItem(): MutableList<AppModel> {
        removeAll { it is AppModel.Spacer }
        add(AppModel.Spacer())
        return this
    }

    class PrivateSpaceHeaderViewHolder(private val binding: AdapterPrivateSpaceHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            appLabelGravity: Int,
            toggleListener: () -> Unit,
            settingsListener: () -> Unit,
        ) = with(binding) {
            privateSpaceTitle.gravity = appLabelGravity
            privateSpaceTitle.setOnClickListener { toggleListener() }
            privateSpaceTitle.setOnLongClickListener {
                settingsListener()
                true
            }
        }
    }

    class SpacerViewHolder(binding: AdapterAppDrawerSpacerBinding) :
        RecyclerView.ViewHolder(binding.root)

    class ViewHolder(private val binding: AdapterAppDrawerBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            flag: Int,
            appLabelGravity: Int,
            focusIndicatorStyle: Int,
            myUserHandle: UserHandle,
            appModel: AppModel,
            clickListener: (AppModel) -> Unit,
            appDeleteListener: (AppModel) -> Unit,
            appInfoListener: (AppModel) -> Unit,
            appHideListener: (AppModel, Int) -> Unit,
            appRenameListener: (AppModel, String) -> Unit,
            appLeftListener: () -> Boolean,
            appBackListener: () -> Boolean,
            appVerticalFocusListener: (Int, Int) -> Boolean,
        ) = with(binding) {
            appHideLayout.visibility = View.GONE
            renameLayout.visibility = View.GONE
            appTitle.visibility = View.VISIBLE

            val isSpecialActionItem = appModel.appPackage == Constants.HomeAction.OPEN_APP_DRAWER

            appTitle.text = buildString {
                append(appModel.appLabel)
                if (appModel.isNew) append(" ✦")
            }
            appTitle.gravity = appLabelGravity
            otherProfileIndicator.isVisible = appModel.user != myUserHandle && !isSpecialActionItem

            appRow.setBackgroundResource(
                if (focusIndicatorStyle == Constants.FocusIndicator.PILL) R.drawable.bg_focus_inverted_selector
                else android.R.color.transparent
            )
            appRow.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                updateFocusAppearance(root.context, appTitle, otherProfileIndicator, hasFocus, focusIndicatorStyle)
            }
            updateFocusAppearance(root.context, appTitle, otherProfileIndicator, appRow.isFocused, focusIndicatorStyle)

            val isPickerFlag = flag != Constants.FLAG_LAUNCH_APP && flag != Constants.FLAG_HIDDEN_APPS
            var dpadFreshPress = false
            var dpadLongPressTriggered = false

            
            
            appRow.setOnClickListener { clickListener(appModel) }
            appRow.setOnLongClickListener {
                if (isSpecialActionItem) return@setOnLongClickListener true
                if (appModel.appPackage.isNotEmpty()) {
                    appDelete.alpha = when (
                        appModel is AppModel.PinnedShortcut || !root.context.isSystemApp(
                            appModel.appPackage,
                            appModel.user
                        )
                    ) {
                        true -> 1.0f
                        false -> 0.5f
                    }
                    appHide.text = if (flag == Constants.FLAG_HIDDEN_APPS)
                        root.context.getString(R.string.adapter_show)
                    else
                        root.context.getString(R.string.adapter_hide)
                    appTitle.visibility = View.INVISIBLE
                    appHide.alpha = when (appModel is AppModel.PinnedShortcut) {
                        true -> 0.5f
                        false -> 1.0f
                    }
                    appHideLayout.visibility = View.VISIBLE
                    appRename.isVisible = flag != Constants.FLAG_HIDDEN_APPS
                }
                true
            }
            appRow.setOnKeyListener { _, keyCode, event ->
                when (keyCode) {
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        when (event.action) {
                            KeyEvent.ACTION_DOWN -> {
                                if (event.repeatCount == 0) {
                                    dpadFreshPress = true
                                    dpadLongPressTriggered = false
                                } else if (dpadFreshPress && !dpadLongPressTriggered) {
                                    dpadLongPressTriggered = true
                                    appRow.performLongClick()
                                }
                                true
                            KeyEvent.ACTION_UP -> {
                                if (dpadFreshPress && !dpadLongPressTriggered) { appRow.performClick() }
                                dpadFreshPress = false
                                dpadLongPressTriggered = false
                                true
                            }

                            else -> false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) appLeftListener() else false
                    }

                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
                        val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                        val position = bindingAdapterPosition
                        if (position == RecyclerView.NO_POSITION) true else appVerticalFocusListener(
                            position,
                            direction
                        )
                    }

                    KeyEvent.KEYCODE_BACK -> {
                        when (event.action) {
                            KeyEvent.ACTION_DOWN -> true
                            KeyEvent.ACTION_UP -> appBackListener()
                            else -> false
                        }
                    }

                    else -> false
                }
            }

            appRename.setOnClickListener {
                if (appModel.appPackage.isNotEmpty()) {
                    etAppRename.hint = getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                    etAppRename.setText(appModel.appLabel)
                    etAppRename.setSelectAllOnFocus(true)
                    renameLayout.visibility = View.VISIBLE
                    appHideLayout.visibility = View.GONE
                    etAppRename.showKeyboard()
                    etAppRename.imeOptions = EditorInfo.IME_ACTION_DONE
                }
            }
            etAppRename.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                appTitle.visibility = if (hasFocus) View.INVISIBLE else View.VISIBLE
            }
            etAppRename.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    etAppRename.hint = getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    etAppRename.hint = ""
                }
            })
            etAppRename.setOnEditorActionListener { _, actionCode, _ ->
                if (actionCode == EditorInfo.IME_ACTION_DONE) {
                    val renameLabel = etAppRename.text.toString().trim()
                    if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                        appRenameListener(appModel, renameLabel)
                        renameLayout.visibility = View.GONE
                    }
                    true
                }
                false
            }
            tvSaveRename.setOnClickListener {
                etAppRename.hideKeyboard()
                val renameLabel = etAppRename.text.toString().trim()
                if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                    appRenameListener(appModel, renameLabel)
                    renameLayout.visibility = View.GONE
                } else {
                    appRenameListener(
                        appModel,
                        getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                    )
                    renameLayout.visibility = View.GONE
                }
            }
            appInfo.setOnClickListener { appInfoListener(appModel) }
            appDelete.setOnClickListener { appDeleteListener(appModel) }
            appMenuClose.setOnClickListener {
                appHideLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
            }
            appRenameClose.setOnClickListener {
                renameLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
            }
            appHide.setOnClickListener { appHideListener(appModel, bindingAdapterPosition) }
        }

        private fun updateFocusAppearance(
            context: Context,
            textView: android.widget.TextView,
            indicatorView: android.widget.ImageView,
            active: Boolean,
            focusIndicatorStyle: Int,
        ) {
            if (focusIndicatorStyle == Constants.FocusIndicator.PILL) {
                textView.setTextColor(
                    if (active) context.getColor(R.color.black)
                    else context.getColorFromAttr(R.attr.primaryColor)
                )
                textView.paintFlags = textView.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
                indicatorView.imageTintList = ColorStateList.valueOf(
                    if (active) context.getColor(R.color.black)
                    else context.getColorFromAttr(R.attr.primaryColor)
                )
            } else {
                textView.setTextColor(context.getColorFromAttr(R.attr.primaryColor))
                textView.paintFlags = if (active) {
                    textView.paintFlags or Paint.UNDERLINE_TEXT_FLAG
                } else {
                    textView.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
                }
                indicatorView.imageTintList = ColorStateList.valueOf(
                    context.getColorFromAttr(R.attr.primaryColor)
                )
            }
        }

        private fun getAppName(context: Context, appPackage: String, user: UserHandle): String {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            return try {
                val activityList = launcherApps.getActivityList(appPackage, user)
                if (activityList.isNotEmpty()) {
                    activityList.first().label.toString()
                } else {
                    val packageManager = context.packageManager
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(appPackage, 0)
                    ).toString()
                }
            } catch (_: Exception) {
                ""
            }
        }
    }
}
