package com.jagrosh.jmusicbot.audio;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.*;
import dev.lavalink.youtube.CannotBeLoaded;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.AndroidVr;
import dev.lavalink.youtube.clients.Music;
import dev.lavalink.youtube.clients.Web;
import dev.lavalink.youtube.clients.WebEmbedded;
import dev.lavalink.youtube.clients.skeleton.Client;
import dev.lavalink.youtube.track.format.TrackFormats;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

public class CobaltClient implements Client {

    private static final Logger logger = LoggerFactory.getLogger(CobaltClient.class);

    private final Web webClient = new Web();
    private final List<Client> clients = List.of(
        new Music(),
        new AndroidVr(),
        webClient,
        new WebEmbedded()
    );
    private final String apiUrl;
    private final String apiKey;

    public CobaltClient(String apiUrl, String apiKey) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return "COBALT";
    }

    @Override
    @NotNull
    public String getPlayerParams() {
        return "";
    }

    @Override
    public boolean canHandleRequest(@NotNull String identifier) {
        for (var client : clients) {
            if (client.canHandleRequest(identifier)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void setPlaylistPageCount(int count) {
        for (var client : clients) {
            client.setPlaylistPageCount(count);
        }
    }

    @Override
    @Nullable
    public TrackFormats loadFormats(
        @NotNull YoutubeAudioSourceManager source,
        @NotNull HttpInterface httpInterface,
        @NotNull String videoId
    ) throws CannotBeLoaded, IOException {
        return webClient.loadFormats(source, httpInterface, videoId);
    }

    @Override
    public AudioItem loadVideo(
        @NotNull YoutubeAudioSourceManager source,
        @NotNull HttpInterface httpInterface,
        @NotNull String videoId
    ) throws IOException {
        var videoUrl = WATCH_URL + videoId;
        var json = loadTrackInfoFromInnertube(httpInterface, videoId);
        var videoDetails = json.get("videoDetails");
        var title = videoDetails.get("title").text();
        var author = videoDetails.get("author").text();
        var length = json.get("streamingData")
            .get("formats")
            .index(0)
            .get("approxDurationMs")
            .asLong(0);
        var thumbnail = videoDetails.get("thumbnail")
            .get("thumbnails")
            .values()
            .getLast()
            .get("url")
            .text();

        var trackInfo = new AudioTrackInfo(
            title,
            author,
            length,
            videoId,
            true,
            videoUrl,
            thumbnail,
            null
        );

        return new CobaltAudioTrack(
            trackInfo,
            source,
            httpInterface,
            apiUrl,
            apiKey
        );
    }

    @Override
    public AudioItem loadSearch(
        @NotNull YoutubeAudioSourceManager source,
        @NotNull HttpInterface httpInterface,
        @NotNull String searchQuery
    ) throws CannotBeLoaded, IOException {
        return loadPlaylistFromClients(
            source,
            httpInterface,
            client -> client.loadSearch(source, httpInterface, searchQuery)
        ).orElse(AudioReference.NO_TRACK);
    }

    @Override
    @Nullable
    public AudioItem loadSearchMusic(
        @NotNull YoutubeAudioSourceManager source,
        @NotNull HttpInterface httpInterface,
        @NotNull String searchQuery
    ) throws CannotBeLoaded, IOException {
        return loadPlaylistFromClients(
            source,
            httpInterface,
            client -> client.loadSearchMusic(source, httpInterface, searchQuery)
        ).orElse(AudioReference.NO_TRACK);
    }

    @Override
    public AudioItem loadMix(
        @NotNull YoutubeAudioSourceManager source,
        @NotNull HttpInterface httpInterface,
        @NotNull String mixId,
        @Nullable String selectedVideoId
    ) throws CannotBeLoaded, IOException {
        return loadPlaylistFromClients(
            source,
            httpInterface,
            client -> client.loadMix(source, httpInterface, mixId, selectedVideoId)
        ).orElseThrow(() -> new FriendlyException(
            "Could not find tracks from mix.",
            FriendlyException.Severity.SUSPICIOUS,
            null
        ));
    }

    @Override
    public AudioItem loadPlaylist(
        @NotNull YoutubeAudioSourceManager source,
        @NotNull HttpInterface httpInterface,
        @NotNull String playlistId,
        @Nullable String selectedVideoId
    ) throws CannotBeLoaded, IOException {
        return loadPlaylistFromClients(
            source,
            httpInterface,
            client -> client.loadPlaylist(source, httpInterface, playlistId, selectedVideoId)
        ).orElseThrow(() -> new FriendlyException(
            "Could not find tracks from playlist.",
            FriendlyException.Severity.SUSPICIOUS,
            null
        ));
    }

    @NotNull
    private JsonBrowser loadTrackInfoFromInnertube(
        @NotNull HttpInterface httpInterface,
        @NotNull String videoId
    ) throws IOException {
        var config = webClient.getBaseClientConfig(httpInterface);

        // Only add embed info if the status is not NON_EMBEDDABLE.
        config.withClientField("clientScreen", "EMBED")
            .withThirdPartyEmbedUrl("https://google.com");

        var payload = config.withRootField("videoId", videoId)
            .withRootField("racyCheckOk", true)
            .withRootField("contentCheckOk", true)
            .withRootField("params", getPlayerParams())
            .setAttributes(httpInterface)
            .toJsonString();

        var request = new HttpPost(PLAYER_URL);
        request.setEntity(new StringEntity(payload, "UTF-8"));

        return loadJsonResponse(httpInterface, request);
    }

    @NotNull
    private JsonBrowser loadJsonResponse(
        @NotNull HttpInterface httpInterface,
        @NotNull HttpPost request
    ) throws IOException {
        if (request.getEntity() instanceof StringEntity) {
            logger.debug("Requesting {} ({}) with payload {}", request.getURI(), "player api response", EntityUtils.toString(request.getEntity(), StandardCharsets.UTF_8));
        } else {
            logger.debug("Requesting {} ({})", "player api response", request.getURI());
        }

        try (var response = httpInterface.execute(request)) {
            HttpClientTools.assertSuccessWithContent(response, "player api response");
            HttpClientTools.assertJsonContentType(response);

            var json = EntityUtils.toString(response.getEntity());
            logger.trace("Response from {} ({}) {}", request.getURI(), "player api response", json);

            return JsonBrowser.parse(json);
        }
    }

    private Optional<AudioItem> loadPlaylistFromClients(
        YoutubeAudioSourceManager source,
        HttpInterface httpInterface,
        PlaylistLoader loader
    ) throws CannotBeLoaded, IOException {
        RuntimeException lastException = null;
        for (var client : clients) {
            try {
                var searchResult = loader.loadPlaylist(client);
                if (searchResult instanceof AudioPlaylist playlist && !playlist.getTracks().isEmpty()) {
                    return Optional.of(convertToCobaltPlaylist(playlist, source, httpInterface));
                }
            } catch (UnsupportedOperationException ignored) {
            } catch (RuntimeException e) {
                lastException = e;
            }
        }
        if (lastException != null) {
            throw lastException;
        } else {
            return Optional.empty();
        }
    }

    private AudioItem convertToCobaltPlaylist(
        AudioPlaylist playlist,
        YoutubeAudioSourceManager source,
        HttpInterface httpInterface
    ) {
        var cobaltTracks = playlist.getTracks()
            .stream()
            .map(track -> {
                var trackInfo = fixTrackInfo(track.getInfo());
                return (AudioTrack) new CobaltAudioTrack(
                    trackInfo,
                    source,
                    httpInterface,
                    apiUrl,
                    apiKey
                );
            })
            .toList();
        var cobaltSelectedTrack = playlist.getSelectedTrack();
        if (cobaltSelectedTrack != null) {
            var trackInfo = fixTrackInfo(cobaltSelectedTrack.getInfo());
            cobaltSelectedTrack = new CobaltAudioTrack(
                trackInfo,
                source,
                httpInterface,
                apiUrl,
                apiKey
            );
        }
        return new BasicAudioPlaylist(
            playlist.getName(),
            cobaltTracks,
            cobaltSelectedTrack,
            playlist.isSearchResult()
        );
    }

    private AudioTrackInfo fixTrackInfo(AudioTrackInfo trackInfo) {
        if (trackInfo.artworkUrl == null) {
            var thumbnail = "https://i.ytimg.com/vi/"
                + trackInfo.identifier
                + "/maxresdefault.jpg";
            return new AudioTrackInfo(
                trackInfo.title,
                trackInfo.author,
                trackInfo.length,
                trackInfo.identifier,
                trackInfo.isStream,
                trackInfo.uri,
                thumbnail,
                null
            );
        } else {
            return trackInfo;
        }
    }

    @FunctionalInterface
    private interface PlaylistLoader {
        AudioItem loadPlaylist(Client client) throws CannotBeLoaded, IOException;
    }
}
