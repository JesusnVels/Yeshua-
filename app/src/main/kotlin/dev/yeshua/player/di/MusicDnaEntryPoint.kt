/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.yeshua.player.musicdna.MusicDnaRepository

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MusicDnaEntryPoint {
    fun musicDnaRepository(): MusicDnaRepository
}
