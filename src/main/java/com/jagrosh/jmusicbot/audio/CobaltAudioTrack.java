package com.jagrosh.jmusicbot.audio;

import com.sedmelluq.discord.lavaplayer.container.MediaContainer;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerDescriptor;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;

import java.net.URI;

public class CobaltAudioTrack extends DelegatedAudioTrack {

    private final AudioSourceManager sourceManager;
    private final HttpInterface httpInterface;
    private final String apiUrl;
    private final String apiKey;

    public CobaltAudioTrack(
        AudioTrackInfo trackInfo,
        AudioSourceManager sourceManager,
        HttpInterface httpInterface,
        String apiUrl,
        String apiKey
    ) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.httpInterface = httpInterface;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        try (var response = httpInterface.execute(createCobaltRequest())) {
            var body = JsonBrowser.parse(response.getEntity().getContent());
            var audioUrl = body.get("url").text();
            if (audioUrl != null) {
                try (var inputStream = new PersistentHttpStream(
                    httpInterface,
                    new URI(audioUrl),
                    Units.CONTENT_LENGTH_UNKNOWN
                )) {
                    var descriptor = new MediaContainerDescriptor(MediaContainer.MP3.probe, null);
                    processDelegate(
                        (InternalAudioTrack) descriptor.createTrack(trackInfo, inputStream),
                        executor
                    );
                    return;
                }
            }
            throw new RuntimeException("Error with Cobalt response:\n" + body.text());
        }
    }

    private HttpPost createCobaltRequest() {
        var url = trackInfo.uri;
        var request = new HttpPost(apiUrl);
        var payload = "{"
            + "\"url\": \"" + url + "\","
            + "\"audioFormat\": \"mp3\","
            + "\"downloadMode\": \"audio\""
            + "}";
        request.setHeader("Accept", "application/json");
        request.setHeader("Content-Type", "application/json");
        if (!apiKey.isBlank()) {
            request.setHeader("Authorization", "Api-Key " + apiKey);
        }
        request.setEntity(new StringEntity(payload, "UTF-8"));
        return request;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new CobaltAudioTrack(
            trackInfo,
            sourceManager,
            httpInterface,
            apiUrl,
            apiKey
        );
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }
}
