/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.queue.AbstractQueue;
import com.jagrosh.jmusicbot.settings.QueueType;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class AudioHandler extends AudioEventAdapter implements AudioSendHandler {

    public final static String PLAY_EMOJI = "▶"; // ▶
    public final static String PAUSE_EMOJI = "⏸"; // ⏸
    public final static String STOP_EMOJI = "⏹"; // ⏹

    private final List<AudioTrack> defaultQueue = new LinkedList<>();
    private final Set<String> votes = new HashSet<>();

    private final PlayerManager manager;
    private final AudioPlayer audioPlayer;
    private final long guildId;

    private AudioFrame lastFrame;
    private AbstractQueue<QueuedTrack> queue;

    protected AudioHandler(PlayerManager manager, Guild guild, AudioPlayer player) {
        this.manager = manager;
        this.audioPlayer = player;
        this.guildId = guild.getIdLong();

        this.setQueueType(manager.getBot().getSettingsManager().getSettings(guildId).getQueueType());
    }

    public void setQueueType(QueueType type) {
        queue = type.createInstance(queue);
    }

    public int addTrackToFront(QueuedTrack track) {
        if (audioPlayer.getPlayingTrack() == null) {
            audioPlayer.playTrack(track.getTrack());
            return -1;
        } else {
            queue.addAt(0, track);
            return 0;
        }
    }

    public int addTrack(QueuedTrack track) {
        if (audioPlayer.getPlayingTrack() == null) {
            audioPlayer.playTrack(track.getTrack());
            return -1;
        } else {
            return queue.add(track);
        }
    }

    public AbstractQueue<QueuedTrack> getQueue() {
        return queue;
    }

    public void stopAndClear() {
        queue.clear();
        defaultQueue.clear();
        audioPlayer.stopTrack();
    }

    public boolean isMusicPlaying(JDA jda) {
        var voiceState = getGuild(jda).getSelfMember().getVoiceState();
        if (voiceState == null) {
            return false;
        }
        return voiceState.inVoiceChannel() && audioPlayer.getPlayingTrack() != null;
    }

    public Set<String> getVotes() {
        return votes;
    }

    public AudioPlayer getPlayer() {
        return audioPlayer;
    }

    public RequestMetadata getRequestMetadata() {
        if (audioPlayer.getPlayingTrack() == null) {
            return RequestMetadata.EMPTY;
        }
        var rm = audioPlayer.getPlayingTrack().getUserData(RequestMetadata.class);
        return rm == null ? RequestMetadata.EMPTY : rm;
    }

    public boolean playFromDefault() {
        if (!defaultQueue.isEmpty()) {
            audioPlayer.playTrack(defaultQueue.removeFirst());
            return true;
        }
        var settings = manager.getBot().getSettingsManager().getSettings(guildId);
        if (settings == null || settings.getDefaultPlaylist() == null) {
            return false;
        }

        var playlist = manager.getBot().getPlaylistLoader().getPlaylist(settings.getDefaultPlaylist());
        if (playlist == null || playlist.getItems().isEmpty()) {
            return false;
        }
        playlist.loadTracks(
            manager,
            (at) -> {
                if (audioPlayer.getPlayingTrack() == null) {
                    audioPlayer.playTrack(at);
                } else {
                    defaultQueue.add(at);
                }
            },
            () -> {
                if (playlist.getTracks().isEmpty() && !manager.getBot().getConfig().getStay()) {
                    manager.getBot().closeAudioConnection(guildId);
                }
            }
        );
        return true;
    }

    // Audio Events
    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        var repeatMode = manager.getBot().getSettingsManager().getSettings(guildId).getRepeatMode();
        // if the track ended normally, and we're in repeat mode, re-add it to the queue
        if (endReason == AudioTrackEndReason.FINISHED && repeatMode != RepeatMode.OFF) {
            var clone = new QueuedTrack(track.makeClone(), track.getUserData(RequestMetadata.class));
            if (repeatMode == RepeatMode.ALL) {
                queue.add(clone);
            } else {
                queue.addAt(0, clone);
            }
        }

        if (queue.isEmpty()) {
            if (!playFromDefault()) {
                manager.getBot().getNowplayingHandler().onTrackUpdate(null);
                if (!manager.getBot().getConfig().getStay()) {
                    manager.getBot().closeAudioConnection(guildId);
                }
                // unpause, in the case when the player was paused and the track has been skipped.
                // this is to prevent the player being paused next time it's being used.
                player.setPaused(false);
            }
        } else {
            var qt = queue.pull();
            player.playTrack(qt.getTrack());
        }
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        LoggerFactory.getLogger("AudioHandler").error("Track {} has failed to play", track.getIdentifier(), exception);
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) {
        votes.clear();
        manager.getBot().getNowplayingHandler().onTrackUpdate(track);
    }

    // Formatting
    public Message getNowPlaying(JDA jda) {
        if (isMusicPlaying(jda)) {
            var guild = getGuild(jda);
            var track = audioPlayer.getPlayingTrack();
            var messageBuilder = new MessageBuilder();
            var voiceState = guild.getSelfMember().getVoiceState();
            if (voiceState != null) {
                var channel = voiceState.getChannel();
                if (channel != null) {
                    messageBuilder.append(FormatUtil.filter(manager.getBot().getConfig().getSuccess() + " **Now Playing in " + channel.getAsMention() + "...**"));
                }
            }
            var embedBuilder = new EmbedBuilder();
            embedBuilder.setColor(guild.getSelfMember().getColor());
            var requestMetadata = getRequestMetadata();
            if (requestMetadata.getOwner() != 0L) {
                var user = guild.getJDA().getUserById(requestMetadata.user.id);
                if (user == null) {
                    embedBuilder.setAuthor(FormatUtil.formatUsername(requestMetadata.user), null, requestMetadata.user.avatar);
                } else {
                    embedBuilder.setAuthor(FormatUtil.formatUsername(user), null, user.getEffectiveAvatarUrl());
                }
            }

            try {
                embedBuilder.setTitle(track.getInfo().title, track.getInfo().uri);
            } catch (Exception e) {
                embedBuilder.setTitle(track.getInfo().title);
            }

            if (manager.getBot().getConfig().useNPImages()) {
                if (track instanceof YoutubeAudioTrack) {
                    embedBuilder.setThumbnail("https://img.youtube.com/vi/" + track.getIdentifier() + "/mqdefault.jpg");
                } else {
                    var thumbnail = track.getInfo().artworkUrl;
                    if (thumbnail != null && !thumbnail.isBlank()) {
                        embedBuilder.setThumbnail(thumbnail);
                    }
                }
            }

            if (track.getInfo().author != null && !track.getInfo().author.isEmpty()) {
                embedBuilder.setFooter("Source: " + track.getInfo().author, null);
            }

            var progress = (double) audioPlayer.getPlayingTrack().getPosition() / track.getDuration();
            embedBuilder.setDescription(getStatusEmoji()
                + " " + FormatUtil.progressBar(progress)
                + " `[" + TimeUtil.formatTime(track.getPosition()) + "/" + TimeUtil.formatTime(track.getDuration()) + "]` "
                + FormatUtil.volumeIcon(audioPlayer.getVolume()));

            return messageBuilder.setEmbeds(embedBuilder.build()).build();
        } else {
            return null;
        }
    }

    public Message getNoMusicPlaying(JDA jda) {
        var guild = getGuild(jda);
        return new MessageBuilder()
            .setContent(FormatUtil.filter(manager.getBot().getConfig().getSuccess() + " **Now Playing...**"))
            .setEmbeds(new EmbedBuilder()
                .setTitle("No music playing")
                .setDescription(STOP_EMOJI + " " + FormatUtil.progressBar(-1) + " " + FormatUtil.volumeIcon(audioPlayer.getVolume()))
                .setColor(guild.getSelfMember().getColor())
                .build()).build();
    }

    public String getStatusEmoji() {
        return audioPlayer.isPaused() ? PAUSE_EMOJI : PLAY_EMOJI;
    }

    @Override
    public boolean canProvide() {
        lastFrame = audioPlayer.provide();
        return lastFrame != null;
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        return ByteBuffer.wrap(lastFrame.getData());
    }

    @Override
    public boolean isOpus() {
        return true;
    }

    private Guild getGuild(JDA jda) {
        return jda.getGuildById(guildId);
    }
}
