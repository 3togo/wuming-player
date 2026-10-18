package android.content;

import android.net.Uri;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Linkage-only Intent. The desktop provider host never resolves or launches Android components, but
 * a few providers build Intents on non-UI paths, so common accessors are supported with stored state.
 */
public class Intent {
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final String ACTION_MAIN = "android.intent.action.MAIN";
    public static final String ACTION_SEND = "android.intent.action.SEND";
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 0x00000001;

    private String action;
    private Uri data;
    private String type;
    private ComponentName component;
    private String packageName;
    private int flags;
    private final Map<String, Object> extras = new HashMap<>();
    private final java.util.Set<String> categories = new java.util.LinkedHashSet<>();

    public Intent() {
    }

    public Intent(String action) {
        this.action = action;
    }

    public Intent(String action, Uri data) {
        this.action = action;
        this.data = data;
    }

    public Intent setAction(String action) {
        this.action = action;
        return this;
    }

    public String getAction() {
        return action;
    }

    public Intent setData(Uri data) {
        this.data = data;
        return this;
    }

    public Uri getData() {
        return data;
    }

    public Intent setType(String type) {
        this.type = type;
        return this;
    }

    public String getType() {
        return type;
    }

    public Intent setClass(android.content.Context context, Class<?> clazz) {
        this.packageName = context.getPackageName();
        this.component = new ComponentName(context.getPackageName(), clazz.getName());
        return this;
    }

    public Intent setClassName(String packageName, String className) {
        this.packageName = packageName;
        this.component = new ComponentName(packageName, className);
        return this;
    }

    public Intent setComponent(ComponentName component) {
        this.component = component;
        return this;
    }

    public ComponentName getComponent() {
        return component;
    }

    public String getPackage() {
        return packageName;
    }

    public Intent addFlags(int flags) {
        this.flags |= flags;
        return this;
    }

    public Intent setFlags(int flags) {
        this.flags = flags;
        return this;
    }

    public int getFlags() {
        return flags;
    }

    public Intent addCategory(String category) {
        categories.add(category);
        return this;
    }

    public boolean hasCategory(String category) {
        return categories.contains(category);
    }

    public Intent putExtra(String name, String value) {
        extras.put(name, value);
        return this;
    }

    public Intent putExtra(String name, int value) {
        extras.put(name, value);
        return this;
    }

    public Intent putExtra(String name, boolean value) {
        extras.put(name, value);
        return this;
    }

    public Intent putExtra(String name, CharSequence value) {
        extras.put(name, value);
        return this;
    }

    public boolean hasExtra(String name) {
        return extras.containsKey(name);
    }

    public String getStringExtra(String name) {
        Object value = extras.get(name);
        return value instanceof String ? (String) value : null;
    }

    public int getIntExtra(String name, int defaultValue) {
        Object value = extras.get(name);
        return value instanceof Integer ? (Integer) value : defaultValue;
    }

    public boolean getBooleanExtra(String name, boolean defaultValue) {
        Object value = extras.get(name);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    public CharSequence getCharSequenceExtra(String name) {
        Object value = extras.get(name);
        return value instanceof CharSequence ? (CharSequence) value : null;
    }

    public Object getSerializableExtra(String name) {
        return extras.get(name);
    }

    public Intent removeExtra(String name) {
        extras.remove(name);
        return this;
    }

    public Set<String> getCategories() {
        return categories;
    }

    @Override
    public String toString() {
        return "Intent{" + action + " " + data + "}";
    }
}
