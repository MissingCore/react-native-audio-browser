// MARK: - Getters

import { nativeBrowser } from '../../native'

/**
 * Gets the current status of replay gain (enabled or disabled).
 */
export function getReplayGainStatus(): boolean {
  return nativeBrowser.getReplayGainStatus()
}

// MARK: - Setters

/**
 * Sets the replay gain status.
 * @param status - Whether replay gain is enabled or disabled.
 */
export function setReplayGainStatus(status: boolean): void {
  nativeBrowser.setReplayGainStatus(status)
}
