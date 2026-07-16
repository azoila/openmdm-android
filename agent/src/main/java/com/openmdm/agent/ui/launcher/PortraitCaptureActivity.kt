package com.openmdm.agent.ui.launcher

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * ZXing's [CaptureActivity] locked to portrait via the manifest entry —
 * the stock capture activity is landscape-only, which is wrong for a
 * launcher flow driven one-handed on a phone.
 */
class PortraitCaptureActivity : CaptureActivity()