package com.wuming.android.tv.bean;


import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.wuming.android.tv.App;
import com.wuming.android.tv.R;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.impl.Diffable;
import com.wuming.tv.core.media.OpenDriveUri;
import com.wuming.tv.core.library.ContentKeyCodec;
import com.wuming.tv.core.library.HistoryPolicy;
import com.wuming.android.tv.cloud.opendrive.OpenDriveSpider;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;

@Entity(indices = {@Index("cid"), @Index("vodName"), @Index(value = {"cid", "vodName"})})
public class History implements Diffable<History> {

    @NonNull
    @PrimaryKey
    @SerializedName("key")
    private String key;
    @SerializedName("vodPic")
    private String vodPic;
    @SerializedName("vodName")
    private String vodName;
    @SerializedName("vodFlag")
    private String vodFlag;
    @SerializedName("vodRemarks")
    private String vodRemarks;
    @SerializedName("episodeUrl")
    private String episodeUrl;
    @SerializedName("mediaUri")
    private String mediaUri;
    @SerializedName("revSort")
    private boolean revSort;
    @SerializedName("revPlay")
    private boolean revPlay;
    @SerializedName("createTime")
    private long createTime;
    @SerializedName("opening")
    private long opening;
    @SerializedName("ending")
    private long ending;
    @SerializedName("position")
    private long position;
    @SerializedName("duration")
    private long duration;
    @SerializedName("speed")
    private float speed;
    @SerializedName("scale")
    private int scale;
    @SerializedName("cid")
    private int cid;

    private transient long updateTime;

    public History() {
        this.speed = 1;
        this.scale = -1;
        this.ending = C.TIME_UNSET;
        this.opening = C.TIME_UNSET;
        this.position = C.TIME_UNSET;
        this.duration = C.TIME_UNSET;
    }

    public static History objectFrom(String str) {
        return App.gson().fromJson(str, History.class);
    }

    public static List<History> arrayFrom(String str) {
        Type listType = new TypeToken<List<History>>() {
        }.getType();
        List<History> items = App.gson().fromJson(str, listType);
        return BeanUtils.emptyListIfNull(items);
    }

    @NonNull
    public String getKey() {
        return key != null ? key : "";
    }

    public void setKey(@NonNull String key) {
        this.key = key;
    }

    public String getVodPic() {
        return BeanUtils.emptyIfNull(vodPic);
    }

    public void setVodPic(String vodPic) {
        this.vodPic = vodPic;
    }

    public String getVodName() {
        return BeanUtils.emptyIfNull(vodName);
    }

    public void setVodName(String vodName) {
        this.vodName = vodName;
    }

    public String getVodFlag() {
        return BeanUtils.emptyIfNull(vodFlag);
    }

    public void setVodFlag(String vodFlag) {
        this.vodFlag = vodFlag;
    }

    public String getVodRemarks() {
        return BeanUtils.emptyIfNull(vodRemarks);
    }

    public void setVodRemarks(String vodRemarks) {
        this.vodRemarks = vodRemarks;
    }

    public String getEpisodeUrl() {
        return BeanUtils.emptyIfNull(episodeUrl);
    }

    public void setEpisodeUrl(String episodeUrl) {
        this.episodeUrl = episodeUrl;
    }

    public String getMediaUri() {
        // Playback resolution and Room persistence run on different executors.
        // Snapshot the nullable field so a concurrent clear cannot occur between
        // validation and the return read and leak null into Room's bindText().
        String value = mediaUri;
        return OpenDriveUri.isValid(value) ? value : "";
    }

    public void setMediaUri(String mediaUri) {
        this.mediaUri = OpenDriveUri.isValid(mediaUri) ? mediaUri : null;
    }

    public boolean hasMediaUri() {
        return !getMediaUri().isEmpty();
    }

    public boolean matchesPlaybackSelection(String flag, Episode episode) {
        return Objects.equals(BeanUtils.emptyIfNull(flag), BeanUtils.emptyIfNull(getVodFlag()))
                && episode != null && episode.matchesName(getEpisode());
    }

    public String getPlaybackId(String flag, Episode episode) {
        if (episode != null && OpenDriveSpider.isRoutedId(episode.getUrl())) {
            return episode.getUrl();
        }
        if (matchesPlaybackSelection(flag, episode) && hasMediaUri()) return getMediaUri();
        if (episode != null && !episode.getUrl().isEmpty()) return episode.getUrl();
        return matchesPlaybackSelection(flag, episode) ? getEpisodeUrl() : "";
    }

    public boolean isRevSort() {
        return revSort;
    }

    public void setRevSort(boolean revSort) {
        this.revSort = revSort;
    }

    public boolean isRevPlay() {
        return revPlay;
    }

    public void setRevPlay(boolean revPlay) {
        this.revPlay = revPlay;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }

    public long getOpening() {
        return opening;
    }

    public void setOpening(long opening) {
        this.opening = opening;
    }

    public long getEnding() {
        return ending;
    }

    public void setEnding(long ending) {
        this.ending = ending;
    }

    public long getPosition() {
        return position;
    }

    public void setPosition(long position) {
        this.position = position;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public float getSpeed() {
        return speed;
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }

    public int getScale() {
        return scale;
    }

    public void setScale(int scale) {
        this.scale = scale;
    }

    public int getCid() {
        return cid;
    }

    public void setCid(int cid) {
        this.cid = cid;
    }

    public String getSiteName() {
        return VodConfig.get().getSite(getSiteKey()).getName();
    }

    public String getSiteKey() {
        return ContentKeyCodec.sourceKey(getKey());
    }

    public String getVodId() {
        return ContentKeyCodec.historyContentId(getKey(), getCid());
    }

    public Flag getFlag() {
        return Flag.create(getVodFlag());
    }

    public Episode getEpisode() {
        return Episode.create(getVodRemarks(), getEpisodeUrl());
    }

    public int getSiteVisible() {
        return BeanUtils.isEmpty(getSiteName()) ? View.GONE : View.VISIBLE;
    }

    public int getRevPlayText() {
        return isRevPlay() ? R.string.play_backward : R.string.play_forward;
    }

    public int getRevPlayHint() {
        return isRevPlay() ? R.string.play_backward_hint : R.string.play_forward_hint;
    }

    public boolean canSave() {
        // A positive position proves that playback started. Duration can remain
        // TIME_UNSET for proxied, progressive, or live-window media, and should
        // not cause an otherwise valid recent item to be discarded.
        return HistoryPolicy.canSave(getPosition());
    }

    public boolean canSync() {
        return HistoryPolicy.canSync(System.currentTimeMillis(), getUpdateTime());
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof History)) return false;

        History it = (History) obj;
        return Objects.equals(getKey(), it.getKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getKey());
    }

    @NonNull
    @Override
    public String toString() {
        return App.gson().toJson(this);
    }

    @Override
    public boolean isSameItem(History other) {
        return equals(other);
    }

    @Override
    public boolean isSameContent(History other) {
        return getVodName().equals(other.getVodName()) && getVodPic().equals(other.getVodPic()) && getCreateTime() == other.getCreateTime();
    }
}
