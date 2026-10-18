package com.wuming.android.tv.bean;
import com.wuming.android.tv.setting.PlayerSettings;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;

import com.wuming.android.tv.App;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.gson.DanmakuAdapter;
import com.wuming.android.tv.gson.FilterAdapter;
import com.wuming.android.tv.gson.HeaderAdapter;
import com.wuming.android.tv.gson.MsgAdapter;
import com.wuming.android.tv.gson.UrlAdapter;
import com.wuming.android.tv.utils.Util;
import com.github.catvod.utils.Trans;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import org.json.JSONObject;
import org.simpleframework.xml.ElementList;
import org.simpleframework.xml.Path;
import org.simpleframework.xml.Root;
import org.simpleframework.xml.core.Persister;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Root(name = "rss", strict = false)
public class Result implements Parcelable {

    // IMPORTANT: When adding, removing, or reordering fields, you MUST update
    // both writeToParcel(Parcel, int) and the Result(Parcel) constructor together.
    // The read/write order must be identical to preserve Parcel integrity.

    @Path("class")
    @ElementList(entry = "ty", required = false, inline = true)
    @SerializedName("class")
    private List<Class> types;

    @Path("list")
    @ElementList(entry = "video", required = false, inline = true)
    @SerializedName("list")
    private List<Vod> list;

    @SerializedName("filters")
    @JsonAdapter(FilterAdapter.class)
    private LinkedHashMap<String, List<Filter>> filters;

    @SerializedName("url")
    @JsonAdapter(UrlAdapter.class)
    private Url url;

    @SerializedName("header")
    @JsonAdapter(HeaderAdapter.class)
    private Map<String, String> header;

    @SerializedName("msg")
    @JsonAdapter(MsgAdapter.class)
    private String msg;

    @SerializedName("danmaku")
    @JsonAdapter(DanmakuAdapter.class)
    private List<Danmaku> danmaku;

    @SerializedName("subs")
    private List<Sub> subs;
    @SerializedName("playUrl")
    private String playUrl;
    @SerializedName("artwork")
    private String artwork;
    @SerializedName("jxFrom")
    private String jxFrom;
    @SerializedName("flag")
    private String flag;
    @SerializedName("desc")
    private String desc;
    @SerializedName("format")
    private String format;
    @SerializedName("click")
    private String click;
    @SerializedName("key")
    private String key;
    @SerializedName("mediaUri")
    private String mediaUri;
    @SerializedName("position")
    private Long position;
    @SerializedName("pagecount")
    private Integer pagecount;
    @SerializedName("parse")
    private Integer parse;
    @SerializedName("code")
    private Integer code;
    @SerializedName("jx")
    private Integer jx;
    @SerializedName("drm")
    private Drm drm;

    public Result() {
    }

    @SuppressWarnings("deprecation")
    protected Result(Parcel in) {
        this.types = new ArrayList<>();
        in.readList(this.types, Class.class.getClassLoader());
        this.list = new ArrayList<>();
        in.readList(this.list, Vod.class.getClassLoader());
        this.msg = in.readString();
        this.playUrl = in.readString();
        this.artwork = in.readString();
        this.jxFrom = in.readString();
        this.flag = in.readString();
        this.desc = in.readString();
        this.format = in.readString();
        this.click = in.readString();
        this.key = in.readString();
        this.position = in.readByte() == 0 ? null : in.readLong();
        this.pagecount = in.readByte() == 0 ? null : in.readInt();
        this.parse = in.readByte() == 0 ? null : in.readInt();
        this.code = in.readByte() == 0 ? null : in.readInt();
        this.jx = in.readByte() == 0 ? null : in.readInt();
        String filtersJson = in.readString();
        if (filtersJson != null) {
            try {
                java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<LinkedHashMap<String, List<Filter>>>(){}.getType();
                this.filters = App.gson().fromJson(filtersJson, type);
            } catch (Exception e) { Log.w("Result", "Parcel filters failed", e); }
        }
        String urlJson = in.readString();
        if (urlJson != null) {
            try { this.url = App.gson().fromJson(urlJson, Url.class); } catch (Exception e) { Log.w("Result", "Parcel url failed", e); }
        }
        String headerJson = in.readString();
        if (headerJson != null) {
            try {
                java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<Map<String, String>>(){}.getType();
                this.header = App.gson().fromJson(headerJson, type);
            } catch (Exception e) { Log.w("Result", "Parcel header failed", e); }
        }
        String danmakuJson = in.readString();
        if (danmakuJson != null) {
            try {
                java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<List<Danmaku>>(){}.getType();
                this.danmaku = App.gson().fromJson(danmakuJson, type);
            } catch (Exception e) { Log.w("Result", "Parcel danmaku failed", e); }
        }
        String subsJson = in.readString();
        if (subsJson != null) {
            try {
                java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<List<Sub>>(){}.getType();
                this.subs = App.gson().fromJson(subsJson, type);
            } catch (Exception e) { Log.w("Result", "Parcel subs failed", e); }
        }
        String drmJson = in.readString();
        if (drmJson != null) {
            try { this.drm = App.gson().fromJson(drmJson, Drm.class); } catch (Exception e) { Log.w("Result", "Parcel drm failed", e); }
        }
        this.mediaUri = in.readString();
    }

    public static Result objectFrom(String str) {
        try {
            return App.gson().fromJson(str, Result.class);
        } catch (Exception e) {
            return empty();
        }
    }

    public static Result fromJson(String str) {
        Result result = objectFrom(str);
        return result == null ? empty() : result.trans();
    }

    public static Result fromXml(String str) {
        try {
            return new Persister().read(Result.class, str, false).trans();
        } catch (Exception e) {
            return empty();
        }
    }

    public static Result fromType(int type, String str) {
        return type == 0 ? fromXml(str) : fromJson(str);
    }

    public static Result fromObject(JSONObject object) {
        return object == null ? empty() : objectFrom(object.toString());
    }

    public static Result empty() {
        return new Result();
    }

    public static Result error(String msg) {
        Result result = new Result();
        result.setParse(0);
        result.setMsg(msg);
        return result;
    }

    public static Result folder(Vod item) {
        Result result = new Result();
        Class type = new Class();
        type.setTypeFlag("1");
        type.setTypeId(item.getId());
        type.setTypeName(item.getName());
        result.setTypes(Arrays.asList(type));
        return result;
    }

    public static Result type(String json) {
        Result result = new Result();
        result.setTypes(Arrays.asList(Class.objectFrom(json)));
        return result.trans();
    }

    public static Result list(List<Vod> items) {
        Result result = new Result();
        result.setList(items);
        return result;
    }

    public static Result vod(Vod item) {
        return list(Arrays.asList(item));
    }

    public static Result purify(Result source, String proxyUrl) {
        Result result = new Result();
        result.url = source.url == null ? Url.create().add(proxyUrl) : source.url.replace(proxyUrl);
        result.header = source.header;
        result.subs = source.subs;
        result.danmaku = source.danmaku;
        result.format = source.format;
        result.drm = source.drm;
        result.parse = source.parse;
        result.jx = source.jx;
        result.flag = source.flag;
        result.playUrl = source.playUrl;
        result.msg = source.msg;
        result.position = source.position;
        result.mediaUri = source.mediaUri;
        return result;
    }

    public List<Class> getTypes() {
        return BeanUtils.emptyListIfNull(types);
    }

    public void setTypes(List<Class> types) {
        this.types = types;
    }

    public List<Vod> getList() {
        return BeanUtils.emptyListIfNull(list);
    }

    public void setList(List<Vod> list) {
        this.list = list;
    }

    public LinkedHashMap<String, List<Filter>> getFilters() {
        return filters == null ? new LinkedHashMap<>(0) : filters;
    }

    public Url getUrl() {
        return url == null ? Url.create() : url;
    }

    public void setUrl(String url) {
        this.url = getUrl().replace(url);
    }

    public String getMsg() {
        return BeanUtils.emptyIfNull(msg).isEmpty() || getCode() != 0 ? "" : msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public List<Sub> getSubs() {
        return subs == null ? new ArrayList<>() : new ArrayList<>(subs);
    }

    public void setSubs(List<Sub> subs) {
        if (getSubs().isEmpty()) this.subs = subs;
    }

    public Map<String, String> getHeader() {
        return BeanUtils.emptyMapIfNull(header);
    }

    public void setHeader(Map<String, String> header) {
        if (getHeader().isEmpty()) this.header = header;
    }

    public void mergeHeader(Map<String, String> extra) {
        if (extra == null || extra.isEmpty()) return;
        Map<String, String> current = getHeader();
        Map<String, String> merged = new java.util.LinkedHashMap<>(current);
        merged.putAll(extra);
        this.header = merged;
    }

    public String getPlayUrl() {
        return BeanUtils.emptyIfNull(playUrl);
    }

    public void setPlayUrl(String playUrl) {
        this.playUrl = playUrl;
    }

    public String getArtwork() {
        return BeanUtils.emptyIfNull(artwork);
    }

    public String getJxFrom() {
        return BeanUtils.emptyIfNull(jxFrom);
    }

    public String getFlag() {
        return BeanUtils.emptyIfNull(flag);
    }

    public void setFlag(String flag) {
        this.flag = flag;
    }

    public String getDesc() {
        return BeanUtils.emptyOrTransform(desc, Util::clean);
    }

    public List<Danmaku> getDanmaku() {
        return !PlayerSettings.isDanmakuLoad() ? Collections.emptyList() : BeanUtils.emptyListIfNull(danmaku);
    }

    public String getFormat() {
        return BeanUtils.emptyIfNull(format);
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getClick() {
        return BeanUtils.emptyIfNull(click);
    }

    public void setClick(String click) {
        this.click = click;
    }

    public String getKey() {
        return BeanUtils.emptyIfNull(key);
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getMediaUri() {
        return BeanUtils.emptyIfNull(mediaUri);
    }

    public void setMediaUri(String mediaUri) {
        this.mediaUri = mediaUri;
    }

    public boolean hasMediaUri() {
        return !getMediaUri().isEmpty();
    }

    public Long getPosition() {
        return position;
    }

    public Integer getPageCount() {
        return BeanUtils.getOrDefault(pagecount, 0);
    }

    public Integer getParse() {
        return BeanUtils.getOrDefault(parse, 0);
    }

    public void setParse(Integer parse) {
        this.parse = parse;
    }

    public Integer getCode() {
        return BeanUtils.getOrDefault(code, 0);
    }

    public Integer getJx() {
        return BeanUtils.getOrDefault(jx, 0);
    }

    public Drm getDrm() {
        return drm;
    }

    public void setDrm(Drm drm) {
        this.drm = drm;
    }

    public boolean hasMsg() {
        return !getMsg().isEmpty();
    }

    public boolean hasArtwork() {
        return !getArtwork().isEmpty();
    }

    public boolean hasPosition() {
        return getPosition() != null;
    }

    public boolean hasDesc() {
        return !getDesc().isEmpty();
    }

    public boolean shouldUseParse() {
        if (!VodConfig.hasParse()) return false;
        return (getPlayUrl().isEmpty() && VodConfig.get().getFlags().contains(getFlag())) || getJx() == 1;
    }

    public boolean needParse() {
        return getParse() == 1 || getJx() == 1;
    }

    public String getRealUrl() {
        return getPlayUrl() + getUrl().v();
    }

    public Style getStyle(Style style) {
        return getList().isEmpty() ? Style.rect() : getList().get(0).getStyle(style);
    }

    public Vod getVod() {
        return getList().isEmpty() ? new Vod() : getList().get(0);
    }

    public Result clear() {
        getList().clear();
        return this;
    }

    public Result trans() {
        if (Trans.pass()) return this;
        getTypes().forEach(Class::trans);
        getList().forEach(Vod::trans);
        getSubs().forEach(Sub::trans);
        return this;
    }

    @NonNull
    @Override
    public String toString() {
        return App.gson().toJson(this);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeList(this.types);
        dest.writeList(this.list);
        dest.writeString(this.msg);
        dest.writeString(this.playUrl);
        dest.writeString(this.artwork);
        dest.writeString(this.jxFrom);
        dest.writeString(this.flag);
        dest.writeString(this.desc);
        dest.writeString(this.format);
        dest.writeString(this.click);
        dest.writeString(this.key);
        if (this.position == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeLong(this.position);
        }
        if (this.pagecount == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeInt(this.pagecount);
        }
        if (this.parse == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeInt(this.parse);
        }
        if (this.code == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeInt(this.code);
        }
        if (this.jx == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeInt(this.jx);
        }
        dest.writeString(this.filters != null ? App.gson().toJson(this.filters) : null);
        dest.writeString(this.url != null ? App.gson().toJson(this.url) : null);
        dest.writeString(this.header != null ? App.gson().toJson(this.header) : null);
        dest.writeString(this.danmaku != null ? App.gson().toJson(this.danmaku) : null);
        dest.writeString(this.subs != null ? App.gson().toJson(this.subs) : null);
        dest.writeString(this.drm != null ? App.gson().toJson(this.drm) : null);
        dest.writeString(this.mediaUri);
    }

    public static final Creator<Result> CREATOR = new Creator<>() {
        @Override
        public Result createFromParcel(Parcel source) {
            return new Result(source);
        }

        @Override
        public Result[] newArray(int size) {
            return new Result[size];
        }
    };
}
