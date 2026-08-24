package app.sidephonelauncher.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.sidephonelauncher.MainViewModel
import app.sidephonelauncher.R
import app.sidephonelauncher.data.AppModel
import app.sidephonelauncher.data.Constants
import app.sidephonelauncher.data.Prefs
import app.sidephonelauncher.databinding.FragmentHomeBinding
import app.sidephonelauncher.helper.NotificationDotRepository
import app.sidephonelauncher.helper.appUsagePermissionGranted
import app.sidephonelauncher.helper.dpToPx
import app.sidephonelauncher.helper.expandNotificationDrawer
import app.sidephonelauncher.helper.getChangedAppTheme
import app.sidephonelauncher.helper.getColorFromAttr
import app.sidephonelauncher.helper.getUserHandleFromString
import app.sidephonelauncher.helper.isPackageInstalled
import app.sidephonelauncher.helper.openAlarmApp
import app.sidephonelauncher.helper.openCalendar
import app.sidephonelauncher.helper.openCameraApp
import app.sidephonelauncher.helper.openDialerApp
import app.sidephonelauncher.helper.openSearch
import app.sidephonelauncher.helper.setPlainWallpaperByTheme
import app.sidephonelauncher.helper.showToast
import app.sidephonelauncher.listener.OnSwipeTouchListener
import app.sidephonelauncher.listener.ViewSwipeTouchListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val notificationDotListener: () -> Unit = {
        activity?.runOnUiThread {
            updateHomeNotificationDotsSafely()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        prefs.ensureSwipeActionDefaults()
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        deviceManager = context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initSwipeTouchListener()
        initClickListeners()
        initKeyNavigation()
    }

    override fun onResume() {
        super.onResume()
        NotificationDotRepository.addListener(notificationDotListener)
        populateHomeScreen(false)
        viewModel.isSidePhOnelauncherDefault()
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()
        requestInitialFocus()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.lock -> {}
            R.id.clock -> openClockApp()
            R.id.date -> openCalendarApp()
            R.id.setDefaultLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.tvScreenTime -> openScreenTimeDigitalWellbeing()
            else -> {
                try {
                    val appLocation = view.tag.toString().toInt()
                    homeAppClicked(appLocation)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun openClockApp() {
        if (prefs.clockAppPackage.isBlank())
            openAlarmApp(requireContext())
        else
            launchApp(
                "Clock",
                prefs.clockAppPackage,
                prefs.clockAppClassName,
                prefs.clockAppUser
            )
    }

    private fun openCalendarApp() {
        if (prefs.calendarAppPackage.isBlank())
            openCalendar(requireContext())
        else
            launchApp(
                "Calendar",
                prefs.calendarAppPackage,
                prefs.calendarAppClassName,
                prefs.calendarAppUser
            )
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.homeApp1 -> showAppList(Constants.FLAG_SET_HOME_APP_1, prefs.appName1.isNotEmpty(), true)
            R.id.homeApp2 -> showAppList(Constants.FLAG_SET_HOME_APP_2, prefs.appName2.isNotEmpty(), true)
            R.id.homeApp3 -> showAppList(Constants.FLAG_SET_HOME_APP_3, prefs.appName3.isNotEmpty(), true)
            R.id.homeApp4 -> showAppList(Constants.FLAG_SET_HOME_APP_4, prefs.appName4.isNotEmpty(), true)
            R.id.homeApp5 -> showAppList(Constants.FLAG_SET_HOME_APP_5, prefs.appName5.isNotEmpty(), true)
            R.id.homeApp6 -> showAppList(Constants.FLAG_SET_HOME_APP_6, prefs.appName6.isNotEmpty(), true)
            R.id.homeApp7 -> showAppList(Constants.FLAG_SET_HOME_APP_7, prefs.appName7.isNotEmpty(), true)
            R.id.homeApp8 -> showAppList(Constants.FLAG_SET_HOME_APP_8, prefs.appName8.isNotEmpty(), true)
            R.id.clock -> {
                showAppList(Constants.FLAG_SET_CLOCK_APP)
                prefs.clockAppPackage = ""
                prefs.clockAppClassName = ""
                prefs.clockAppUser = ""
            }

            R.id.date -> {
                showAppList(Constants.FLAG_SET_CALENDAR_APP)
                prefs.calendarAppPackage = ""
                prefs.calendarAppClassName = ""
                prefs.calendarAppUser = ""
            }

            R.id.tvScreenTime -> {
                showAppList(Constants.FLAG_SET_SCREEN_TIME_APP)
                prefs.screenTimeAppPackage = ""
                prefs.screenTimeAppClassName = ""
                prefs.screenTimeAppUser = ""
            }

            R.id.setDefaultLauncher -> {
                prefs.hideSetDefaultLauncher = true
                binding.setDefaultLauncher.visibility = View.GONE
                if (viewModel.isSidePhOnelauncherDefault.value != true) {
                    requireContext().showToast(R.string.set_as_default_launcher)
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                }
            }
        }
        return true
    }

    private fun initObservers() {
        binding.firstRunTips.visibility = View.GONE
        binding.setDefaultLauncher.visibility = View.GONE

        viewModel.firstOpen.observe(viewLifecycleOwner) {
            if (it == true) {
                viewModel.showDialog.postValue(Constants.Dialog.HOME_TIPS)
                viewModel.firstOpen(false)
            }
        }
        viewModel.refreshHome.observe(viewLifecycleOwner) {
            populateHomeScreen(it)
        }
        viewModel.isSidePhOnelauncherDefault.observe(viewLifecycleOwner, Observer {
            if (it != true) {
                if (prefs.dailyWallpaper && prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES) {
                    prefs.dailyWallpaper = false
                    viewModel.cancelWallpaperWorker()
                }
            }
            binding.setDefaultLauncher.isVisible = false
        })
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            setHomeAlignment(it)
        }
        viewModel.toggleDateTime.observe(viewLifecycleOwner) {
            populateDateTime()
        }
        viewModel.screenTimeValue.observe(viewLifecycleOwner) {
            it?.let { binding.tvScreenTime.text = it }
        }
    }

    private fun initKeyNavigation() {
        binding.mainLayout.isFocusable = true
        binding.mainLayout.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.mainLayout.post {
                    redirectRootFocusToHomeTargetSafely()
                }
            }
        }
        binding.mainLayout.setOnKeyListener { _, keyCode, event ->
            handleHomeKeyEvent(keyCode, event)
        }
    }

    fun handleSideDpadKeyEvent(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    openSwipeRightApp()
                }
                event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    openSwipeLeftApp()
                }
                event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
            }

            else -> false
        }
    }

    fun shouldHandleUnfocusedDpadKeyEvent(): Boolean {
        val binding = _binding ?: return false
        val focusedView = activity?.currentFocus
        return focusedView == null
                || focusedView == binding.mainLayout
                || getFocusableHomeTargets().none { it == focusedView }
    }

    fun handleUnfocusedDpadKeyEvent(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    openSwipeRightApp()
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    openSwipeLeftApp()
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    redirectRootFocusToHomeTargetSafely()
                }
                true
            }

            else -> false
        }
    }

    private fun handleHomeKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        val binding = _binding ?: return false
        val visibleApps = getVisibleHomeApps()

        if (activity?.currentFocus == binding.mainLayout) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        redirectRootFocusToHomeTargetSafely()
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        openSwipeRightApp()
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        openSwipeLeftApp()
                    }
                    return true
                }
            }
        }

        val focusedIndex = visibleApps.indexOf(activity?.currentFocus)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (event.repeatCount > 0) return true
                        when {
                            focusedIndex > 0 -> visibleApps[focusedIndex - 1].requestFocus()
                            focusedIndex == 0 -> getBottomHomeHeaderTarget()?.requestFocus() ?: swipeDownAction()
                            else -> return false
                        }
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.repeatCount > 0) return true
                        if (focusedIndex < visibleApps.size - 1) {
                            visibleApps[focusedIndex + 1].requestFocus()
                        }
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (event.repeatCount > 0) return true
                        openSwipeRightApp()
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.repeatCount > 0) return true
                        openSwipeLeftApp()
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> return false
                }
            }

            KeyEvent.ACTION_UP -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> return true
                    KeyEvent.KEYCODE_DPAD_LEFT -> return true
                    KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> return false
                }
            }
        }
        return false
    }

    private fun requestInitialFocus() {
        binding.mainLayout.post {
            ensureHomeFocus()
        }
    }

    fun ensureHomeFocus(): Boolean {
        if (_binding == null) return false
        val target = getPreferredHomeFocusTarget() ?: return false
        val focusedView = activity?.currentFocus
        if (focusedView == target && target.hasFocus()) return true
        return target.requestFocus()
    }

    private fun rememberHomeFocus(view: View, hasFocus: Boolean) {
        if (view is TextView && view !is HomeAppTextView) {
            updateHeaderFocusAppearance(view, hasFocus)
        }
        if (!hasFocus) return
        viewModel.setLastHomeFocusedView(view.id)
    }

    private fun updateHeaderFocusAppearance(textView: TextView, active: Boolean) {
        if (prefs.focusIndicatorStyle == Constants.FocusIndicator.PILL) {
            textView.setBackgroundResource(R.drawable.bg_focus_inverted_selector)
            textView.setTextColor(
                if (active) requireContext().getColor(R.color.black)
                else requireContext().getColorFromAttr(R.attr.primaryColor)
            )
            textView.paintFlags = textView.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        } else {
            textView.setBackgroundResource(android.R.color.transparent)
            textView.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColor))
            textView.paintFlags = if (active) {
                textView.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            } else {
                textView.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            }
        }
        textView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }

    private fun redirectRootFocusToHomeTargetSafely() {
        ensureHomeFocus()
    }

    private fun getPreferredHomeFocusTarget(): View? {
        val binding = _binding ?: return null
        val firstVisibleHomeApp = getVisibleHomeApps().firstOrNull()
        if (viewModel.consumeResetHomeFocusToFirstApp()) {
            return firstVisibleHomeApp ?: getTopHomeHeaderTargets().firstOrNull()
        }

        val rememberedTarget = viewModel.lastHomeFocusedViewId
            ?.let { binding.root.findViewById<View>(it) }
            ?.takeIf { it.visibility == View.VISIBLE && it.isFocusable }

        return rememberedTarget
            ?: firstVisibleHomeApp
            ?: getTopHomeHeaderTargets().firstOrNull()
    }

    private fun getBottomHomeHeaderTarget(): View? {
        return getTopHomeHeaderTargets().lastOrNull()
    }

    private fun getTopHomeHeaderTargets(): List<View> {
        val binding = _binding ?: return emptyList()
        return buildList {
            if (binding.clock.isVisible) add(binding.clock)
            if (binding.date.isVisible) add(binding.date)
        }
    }

    private fun getFocusableHomeTargets(): List<View> {
        return getTopHomeHeaderTargets() + getVisibleHomeApps()
    }

    private fun getVisibleHomeApps(): List<View> {
        val binding = _binding ?: return emptyList()
        return listOf(
            binding.homeApp1,
            binding.homeApp2,
            binding.homeApp3,
            binding.homeApp4,
            binding.homeApp5,
            binding.homeApp6,
            binding.homeApp7,
            binding.homeApp8,
        ).filter { it.visibility == View.VISIBLE }
    }

    private fun initSwipeTouchListener() {
        val context = requireContext()
        binding.mainLayout.setOnTouchListener(getSwipeGestureListener(context))
        binding.homeApp1.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp1))
        binding.homeApp2.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp2))
        binding.homeApp3.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp3))
        binding.homeApp4.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp4))
        binding.homeApp5.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp5))
        binding.homeApp6.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp6))
        binding.homeApp7.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp7))
        binding.homeApp8.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp8))
    }

    private fun initClickListeners() {
        binding.lock.setOnClickListener(this)
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.clock.setOnLongClickListener(this)
        binding.date.setOnLongClickListener(this)
        binding.clock.onFocusChangeListener = View.OnFocusChangeListener { view, hasFocus ->
            rememberHomeFocus(view, hasFocus)
        }
        binding.date.onFocusChangeListener = View.OnFocusChangeListener { view, hasFocus ->
            rememberHomeFocus(view, hasFocus)
        }
        binding.setDefaultLauncher.setOnClickListener(this)
        binding.setDefaultLauncher.setOnLongClickListener(this)
        binding.tvScreenTime.setOnClickListener(this)
        binding.tvScreenTime.setOnLongClickListener(this)

        listOf(binding.clock, binding.date).forEach { headerView ->
            var dpadFreshPress = false
            var dpadLongPressTriggered = false
            headerView.setOnKeyListener { view, keyCode, event ->
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return@setOnKeyListener true
                        val headerTargets = getTopHomeHeaderTargets()
                        val currentIndex = headerTargets.indexOf(view)
                        if (currentIndex > 0) {
                            headerTargets[currentIndex - 1].requestFocus()
                        }else { swipeDownAction() }
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return@setOnKeyListener true
                        val headerTargets = getTopHomeHeaderTargets()
                        val currentIndex = headerTargets.indexOf(view)
                        when {
                            currentIndex in 0 until headerTargets.lastIndex -> {
                                headerTargets[currentIndex + 1].requestFocus()
                            }

                            else -> {
                                getVisibleHomeApps().firstOrNull()?.requestFocus()
                            }
                        }
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
                        if (event.repeatCount > 0) return@setOnKeyListener true
                        openSwipeRightApp()
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
                        if (event.repeatCount > 0) return@setOnKeyListener true
                        openSwipeLeftApp()
                        true
                    }

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
                                    view.performLongClick()
                                }
                                true
                            }

                            KeyEvent.ACTION_UP -> {
                                if (dpadFreshPress && !dpadLongPressTriggered) view.performClick()
                                dpadFreshPress = false
                                dpadLongPressTriggered = false
                                true
                            }

                            else -> false
                        }
                    }

                    else -> false
                }
            }
        }

        listOf(
            binding.homeApp1,
            binding.homeApp2,
            binding.homeApp3,
            binding.homeApp4,
            binding.homeApp5,
            binding.homeApp6,
            binding.homeApp7,
            binding.homeApp8,
        ).forEach { homeApp ->
            homeApp.setOnClickListener(this)
            homeApp.setOnLongClickListener(this)
            homeApp.onFocusChangeListener = View.OnFocusChangeListener { view, hasFocus ->
                rememberHomeFocus(view, hasFocus)
            }
            var dpadFreshPress = false
            var dpadLongPressTriggered = false
            homeApp.setOnKeyListener { view, keyCode, event ->
                when (keyCode) {
                    KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4 -> {
	                    val appIndex = when (keyCode) {
	                        KeyEvent.KEYCODE_F1 -> 1
	                        KeyEvent.KEYCODE_F2 -> 2
	                        KeyEvent.KEYCODE_F3 -> 3
	                        KeyEvent.KEYCODE_F4 -> 4
	                        else -> 0
    	            	}
	                    if (appIndex != 0) {
	                	    homeAppClicked(appIndex)
	                        true
	            	    }else { false }
                    }
                    
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
                        if (event.repeatCount > 0) return@setOnKeyListener true
                        val visibleApps = getVisibleHomeApps()
                        val focusedIndex = visibleApps.indexOf(view)
                        if (focusedIndex < 0) return@setOnKeyListener true
                        if (focusedIndex < visibleApps.size - 1) {
                            visibleApps[focusedIndex + 1].requestFocus()
                        }
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
                        if (event.repeatCount > 0) return@setOnKeyListener true
                        openSwipeRightApp()
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
                        if (event.repeatCount > 0) return@setOnKeyListener true
                        openSwipeLeftApp()
                        true
                    }

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
                                    view.performLongClick()
                                }
                                true
                            }

                            KeyEvent.ACTION_UP -> {
                                if (dpadFreshPress && !dpadLongPressTriggered) view.performClick()
                                dpadFreshPress = false
                                dpadLongPressTriggered = false
                                true
                            }

                            else -> false
                        }
                    }

                    else -> false
                }
            }
        }
    }

    private fun setHomeAlignment(horizontalGravity: Int = prefs.homeAlignment) {
        val verticalGravity = if (prefs.homeBottomAlignment) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        binding.homeAppsLayout.gravity = horizontalGravity or verticalGravity
        binding.dateTimeLayout.gravity = horizontalGravity
        homeAppTextViews().forEach { it.gravity = horizontalGravity }
    }

    private fun populateDateTime() {
        binding.dateTimeLayout.isVisible = prefs.dateTimeVisibility != Constants.DateTime.OFF
        binding.clock.isVisible = Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility)
        binding.date.isVisible = Constants.DateTime.isDateVisible(prefs.dateTimeVisibility)

        val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        var dateText = dateFormat.format(Date())

        if (!prefs.showStatusBar) {
            val battery = (requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (battery > 0)
                dateText = getString(R.string.day_battery, dateText, battery)
        }
        binding.date.text = dateText.replace(".,", ",")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun populateScreenTime() {
        if (requireContext().appUsagePermissionGranted().not()) return

        viewModel.getTodaysScreenTime()
        binding.tvScreenTime.visibility = View.VISIBLE

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val horizontalMargin = if (isLandscape) 64.dpToPx() else 10.dpToPx()
        val marginTop = if (isLandscape) {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 36.dpToPx() else 56.dpToPx()
        } else {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 45.dpToPx() else 72.dpToPx()
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = marginTop
            marginStart = horizontalMargin
            marginEnd = horizontalMargin
            gravity = if (prefs.homeAlignment == Gravity.END) Gravity.START else Gravity.END
        }
        binding.tvScreenTime.layoutParams = params
        binding.tvScreenTime.setPadding(10.dpToPx())
    }

    private fun populateHomeScreen(appCountUpdated: Boolean) {
        if (appCountUpdated) hideHomeApps()
        restoreHomeAppFocusability()
        populateDateTime()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            populateScreenTime()

        val homeAppsNum = prefs.homeAppsNum
        if (homeAppsNum == 0) {
            binding.mainLayout.post { ensureHomeFocus() }
            return
        }

        binding.homeApp1.visibility = View.VISIBLE
        if (!setHomeAppText(
                binding.homeApp1 as TextView,
                prefs.appName1,
                prefs.appPackage1,
                prefs.appUser1,
                prefs.isShortcut1,
                prefs.shortcutId1
            )
        ) {
            prefs.appName1 = ""
            prefs.appPackage1 = ""
        }
        if (homeAppsNum == 1) {
            binding.mainLayout.post { ensureHomeFocus() }
            return
        }

        binding.homeApp2.visibility = View.VISIBLE
        if (!setHomeAppText(
                binding.homeApp2 as TextView,
                prefs.appName2,
                prefs.appPackage2,
                prefs.appUser2,
                prefs.isShortcut2,
                prefs.shortcutId2
            )
        ) {
            prefs.appName2 = ""
            prefs.appPackage2 = ""
        }
        if (homeAppsNum == 2) {
            binding.mainLayout.post { ensureHomeFocus() }
            return
        }

        binding.homeApp3.visibility = View.VISIBLE
        if (!setHomeAppText(
                binding.homeApp3 as TextView,
                prefs.appName3,
                prefs.appPackage3,
                prefs.appUser3,
                prefs.isShortcut3,
                prefs.shortcutId3
            )
        ) {
            prefs.appName3 = ""
            prefs.appPackage3 = ""
        }
        if (homeAppsNum == 3) {
            binding.mainLayout.post { ensureHomeFocus() }
            return
        }

        binding.homeApp4.visibility = View.VISIBLE
        if (!setHomeAppText(
                binding.homeApp4 as TextView,
                prefs.appName4,
                prefs.appPackage4,
                prefs.appUser4,
                prefs.isShortcut4,
                prefs.shortcutId4
            )
        ) {
            prefs.appName4 = ""
            prefs.appPackage4 = ""
        }
        if (homeAppsNum == 4) {
            binding.mainLayout.post { ensureHomeFocus() }
            return
        }

        binding.homeApp5.visibility = View.VISIBLE
        if (!setHomeAppText(
                binding.homeApp5 as TextView,
                prefs.appName5,
                prefs.appPackage5,
                prefs.appUser5,
                prefs.isShortcut5,
                prefs.shortcutId5
            )
        ) {
            prefs.appName5 = ""
            prefs.appPackage5 = ""
        }
        if (homeAppsNum == 5) {
            binding.mainLayout.post { ensureHomeFocus() }
            return
        }

        binding.homeApp6.visibility = View.VISIBLE
        if (!setHomeAppText(
                binding.homeApp6 as TextView,
                prefs.appName6,
                prefs.appPackage6,
                prefs.appUser6,
                prefs.isShortcut6,
                prefs.shortcutId6
            )
        ) {
            prefs.appName6 = ""
            prefs.appPackage6 = ""
        }
        if (homeAppsNum == 6) {
            binding.mainLayout.post { ensureHomeFocus() }
            return
        }

        binding.homeApp7.visibility = View.VISIBLE
        if (!setHomeAppText(
                binding.homeApp7 as TextView,
                prefs.appName7,
                prefs.appPackage7,
                prefs.appUser7,
                prefs.isShortcut7,
                prefs.shortcutId7
            )
        ) {
            prefs.appName7 = ""
            prefs.appPackage7 = ""
        }
        if (homeAppsNum == 7) {
            binding.mainLayout.post { ensureHomeFocus() }
            return
        }

        binding.homeApp8.visibility = View.VISIBLE
        if (!setHomeAppText(
                binding.homeApp8 as TextView,
                prefs.appName8,
                prefs.appPackage8,
                prefs.appUser8,
                prefs.isShortcut8,
                prefs.shortcutId8
            )
        ) {
            prefs.appName8 = ""
            prefs.appPackage8 = ""
        }
        binding.mainLayout.post { ensureHomeFocus() }
    }

    private fun setHomeAppText(
        textView: TextView,
        appName: String,
        packageName: String,
        userString: String,
        isShortcut: Boolean,
        shortcutId: String?,
    ): Boolean {
        val userHandle = getUserHandleFromString(requireContext(), userString)

        if (isShortcut) {
            val launcherApps = requireContext().getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(packageName)
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            }

            try {
                val shortcuts = launcherApps.getShortcuts(query, userHandle)
                if (shortcuts?.any { it.id == shortcutId } == true) {
                    textView.text = appName
                    updateHomeAppDot(textView, packageName)
                    return true
                }
                textView.text = ""
                updateHomeAppDot(textView, null)
                return false
            } catch (e: Exception) {
                e.printStackTrace()
                textView.text = ""
                updateHomeAppDot(textView, null)
                return false
            }
        }

        if (isPackageInstalled(requireContext(), packageName, userString)) {
            textView.text = appName
            updateHomeAppDot(textView, packageName)
            return true
        }
        textView.text = ""
        updateHomeAppDot(textView, null)
        return false
    }

    private fun updateHomeNotificationDotsSafely() {
        val binding = _binding ?: return
        updateHomeAppDot(binding.homeApp1 as TextView, prefs.appPackage1)
        updateHomeAppDot(binding.homeApp2 as TextView, prefs.appPackage2)
        updateHomeAppDot(binding.homeApp3 as TextView, prefs.appPackage3)
        updateHomeAppDot(binding.homeApp4 as TextView, prefs.appPackage4)
        updateHomeAppDot(binding.homeApp5 as TextView, prefs.appPackage5)
        updateHomeAppDot(binding.homeApp6 as TextView, prefs.appPackage6)
        updateHomeAppDot(binding.homeApp7 as TextView, prefs.appPackage7)
        updateHomeAppDot(binding.homeApp8 as TextView, prefs.appPackage8)
    }

    private fun homeAppTextViews(): List<TextView> {
        return listOf(
            binding.homeApp1 as TextView,
            binding.homeApp2 as TextView,
            binding.homeApp3 as TextView,
            binding.homeApp4 as TextView,
            binding.homeApp5 as TextView,
            binding.homeApp6 as TextView,
            binding.homeApp7 as TextView,
            binding.homeApp8 as TextView,
        )
    }

    private fun updateHomeAppDot(textView: TextView, packageName: String?) {
        (textView as? HomeAppTextView)?.setShowNotificationDot(
            packageName.isNullOrBlank().not() && NotificationDotRepository.hasActiveNotification(packageName)
        )
    }

    private fun restoreHomeAppFocusability() {
        listOf(
            binding.homeApp1,
            binding.homeApp2,
            binding.homeApp3,
            binding.homeApp4,
            binding.homeApp5,
            binding.homeApp6,
            binding.homeApp7,
            binding.homeApp8,
        ).forEach {
            it.isFocusable = true
            it.isFocusableInTouchMode = true
        }
        listOf(binding.clock as TextView, binding.date).forEach {
            updateHeaderFocusAppearance(it, it.hasFocus())
        }
    }

    private fun hideHomeApps() {
        listOf(
            binding.homeApp1,
            binding.homeApp2,
            binding.homeApp3,
            binding.homeApp4,
            binding.homeApp5,
            binding.homeApp6,
            binding.homeApp7,
            binding.homeApp8,
        ).forEach {
            it.visibility = View.GONE
            it.isFocusable = false
            it.isFocusableInTouchMode = false
        }
    }

    private fun launchAppOrShortcut(
        appName: String,
        packageName: String,
        activityClassName: String?,
        shortcutId: String?,
        isShortcut: Boolean,
        userString: String,
        fallback: (() -> Unit)? = null,
    ) {
        if (appName.isEmpty()) {
            showLongPressToast()
            return
        }
        if (isShortcut && !shortcutId.isNullOrEmpty()) {
            launchShortcut(
                packageName = packageName,
                shortcutId = shortcutId,
                shortcutLabel = appName,
                userString = userString
            )
        } else if (packageName.isNotEmpty()) {
            launchApp(
                appName = appName,
                packageName = packageName,
                activityClassName = activityClassName,
                userString = userString
            )
        } else {
            fallback?.invoke()
        }
    }

    private fun launchShortcut(shortcutId: String, packageName: String, shortcutLabel: String, userString: String) {
        viewModel.selectedApp(
            AppModel.PinnedShortcut(
                shortcutId = shortcutId,
                appLabel = shortcutLabel,
                user = getUserHandleFromString(requireContext(), userString),
                key = null,
                appPackage = packageName,
                isNew = false,
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun launchApp(appName: String, packageName: String, activityClassName: String?, userString: String) {
        viewModel.selectedApp(
            AppModel.App(
                appLabel = appName,
                key = null,
                appPackage = packageName,
                activityClassName = activityClassName,
                isNew = false,
                user = getUserHandleFromString(requireContext(), userString)
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun homeAppClicked(location: Int) {
        launchAppOrShortcut(
            appName = prefs.getAppName(location),
            packageName = prefs.getAppPackage(location),
            activityClassName = prefs.getAppActivityClassName(location),
            shortcutId = prefs.getShortcutId(location),
            isShortcut = prefs.getIsShortcut(location),
            userString = prefs.getAppUser(location)
        )
    }

    private fun openSwipeRightApp() {
        if (!prefs.swipeRightEnabled) return
        launchHomeSideAction(
            appName = prefs.appNameSwipeRight,
            packageName = prefs.appPackageSwipeRight,
            activityClassName = prefs.appActivityClassNameRight,
            shortcutId = prefs.shortcutIdSwipeRight,
            isShortcut = prefs.isShortcutSwipeRight,
            userString = prefs.appUserSwipeRight,
        )
    }

    private fun openSwipeLeftApp() {
        if (!prefs.swipeLeftEnabled) return
        launchHomeSideAction(
            appName = prefs.appNameSwipeLeft,
            packageName = prefs.appPackageSwipeLeft,
            activityClassName = prefs.appActivityClassNameSwipeLeft,
            shortcutId = prefs.shortcutIdSwipeLeft,
            isShortcut = prefs.isShortcutSwipeLeft,
            userString = prefs.appUserSwipeLeft,
        )
    }

    private fun launchHomeSideAction(
        appName: String,
        packageName: String,
        activityClassName: String?,
        shortcutId: String?,
        isShortcut: Boolean,
        userString: String,
        fallback: (() -> Unit)? = null,
    ) {
        when (packageName) {
            Constants.HomeAction.OPEN_APP_DRAWER -> {
                showAppList(Constants.FLAG_LAUNCH_APP)
                return
            }

            Constants.HomeAction.OPEN_PHONE -> {
                openDialerApp(requireContext())
                return
            }
        }
        launchAppOrShortcut(
            appName = appName,
            packageName = packageName,
            activityClassName = activityClassName,
            shortcutId = shortcutId,
            isShortcut = isShortcut,
            userString = userString,
            fallback = fallback,
        )
    }

    private fun showAppList(flag: Int, rename: Boolean = false, includeHiddenApps: Boolean = false) {
        viewModel.getAppList(includeHiddenApps)
        try {
            findNavController().navigate(
                R.id.action_mainFragment_to_appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
        } catch (e: Exception) {
            findNavController().navigate(
                R.id.appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
            e.printStackTrace()
        }
    }

    private fun swipeDownAction() {
        when (prefs.swipeDownAction) {
            Constants.SwipeDownAction.SEARCH -> openSearch(requireContext())
            else -> expandNotificationDrawer(requireContext())
        }
    }

    private fun lockPhone() {
        requireActivity().runOnUiThread {
            try {
                deviceManager.lockNow()
            } catch (e: SecurityException) {
                requireContext().showToast(getString(R.string.please_turn_on_double_tap_to_unlock), Toast.LENGTH_LONG)
                findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            } catch (e: Exception) {
                requireContext().showToast(getString(R.string.launcher_failed_to_lock_device), Toast.LENGTH_LONG)
                prefs.lockModeOn = false
            }
        }
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.show(WindowInsets.Type.statusBars())
        else
            @Suppress("DEPRECATION", "InlinedApi")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.hide(WindowInsets.Type.statusBars())
        else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
    }

    private fun changeAppTheme() {
        if (prefs.dailyWallpaper.not()) return
        val changedAppTheme = getChangedAppTheme(requireContext(), prefs.appTheme)
        prefs.appTheme = changedAppTheme
        if (prefs.dailyWallpaper) {
            setPlainWallpaperByTheme(requireContext(), changedAppTheme)
            viewModel.setWallpaperWorker()
        }
        requireActivity().recreate()
    }

    private fun openScreenTimeDigitalWellbeing() {
        if (prefs.screenTimeAppPackage.isNotBlank()) {
            launchApp(
                "Screen Time",
                prefs.screenTimeAppPackage,
                prefs.screenTimeAppClassName,
                prefs.screenTimeAppUser
            )
            return
        }
        val intent = Intent()
        try {
            intent.setClassName(
                Constants.DIGITAL_WELLBEING_PACKAGE_NAME,
                Constants.DIGITAL_WELLBEING_ACTIVITY
            )
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                intent.setClassName(
                    Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME,
                    Constants.DIGITAL_WELLBEING_SAMSUNG_ACTIVITY
                )
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showLongPressToast() = requireContext().showToast(getString(R.string.long_press_to_select_app))

    private fun textOnClick(view: View) = onClick(view)

    private fun textOnLongClick(view: View) = onLongClick(view)

    private fun getSwipeGestureListener(context: Context): View.OnTouchListener {
        return object : OnSwipeTouchListener(context) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                swipeDownAction()
            }

            override fun onLongClick() {
                super.onLongClick()
                try {
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                    viewModel.firstOpen(false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onDoubleClick() {
                super.onDoubleClick()
                if (!prefs.lockModeOn) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    binding.lock.performClick()
                else
                    lockPhone()
            }

            override fun onClick() {
                super.onClick()
                viewModel.checkForMessages.call()
            }
        }
    }

    private fun getViewSwipeTouchListener(context: Context, view: View): View.OnTouchListener {
        return object : ViewSwipeTouchListener(context, view) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                swipeDownAction()
            }

            override fun onLongClick(view: View) {
                super.onLongClick(view)
                textOnLongClick(view)
            }

            override fun onClick(view: View) {
                super.onClick(view)
                textOnClick(view)
            }
        }
    }

    override fun onPause() {
        NotificationDotRepository.removeListener(notificationDotListener)
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
