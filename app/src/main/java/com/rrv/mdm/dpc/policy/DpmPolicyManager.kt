package com.rrv.mdm.dpc.policy

import com.rrv.mdm.dpc.mdm.device.DeviceManagementManager

/**
 * Authoritative Typealias for MDM Device & Policy Management.
 * Enforces Ponytail Single Source of Truth: all DPM hardware restrictions, app governance,
 * kiosk features, and system controls are centrally resolved by DeviceManagementManager.
 */
typealias DpmPolicyManager = DeviceManagementManager
