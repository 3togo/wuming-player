package com.wuming.tv.guardhost;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AndroidJsonCompatibilityTest {

    @Test
    public void getStringCoercesNumericProviderFieldsLikeAndroid() throws Exception {
        JSONObject object = new JSONObject("{\"vodId\":93111}");

        assertEquals("93111", object.getString("vodId"));
    }
}
