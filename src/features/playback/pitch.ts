// MARK: - Getters

import { nativeBrowser } from '../../native'

/**
 * Gets the playback pitch.
 */
export function getPitch(): number {
  return nativeBrowser.getPitch()
}

// MARK: - Setters

/**
 * Sets the playback pitch.
 * @param pitch - The playback pitch to change to.
 */
export function setPitch(pitch: number): void {
  nativeBrowser.setPitch(pitch)
}
