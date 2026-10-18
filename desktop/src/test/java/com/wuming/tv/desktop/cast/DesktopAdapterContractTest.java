package com.wuming.tv.desktop.cast;

import static org.junit.Assert.*;

import org.junit.Test;
import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.model.types.UnsignedIntegerTwoBytes;
import org.fourthline.cling.support.avtransport.AVTransportException;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable;
import org.fourthline.cling.support.model.TransportAction;
import org.fourthline.cling.support.model.TransportState;
import org.fourthline.cling.support.renderingcontrol.RenderingControlException;
import org.fourthline.cling.support.renderingcontrol.lastchange.RenderingControlVariable;

public class DesktopAdapterContractTest {
    private final UnsignedIntegerFourBytes id = new UnsignedIntegerFourBytes(0);
    private static final class Player implements CastPlayer {
        String state = "STOPPED", uri = "";
        double position;
        int volume = 50, loads;
        boolean muted, closed, failStop;
        public void load(String uri) { this.uri = uri; loads++; state = "PAUSED_PLAYBACK"; }
        public void play() { state = "PLAYING"; }
        public void pause() { state = "PAUSED_PLAYBACK"; }
        public void stop() { if (failStop) throw new IllegalStateException(); state = "STOPPED"; }
        public void seek(double seconds) { position = seconds; }
        public void volume(int value) { volume = value; }
        public void mute(boolean value) { muted = value; }
        public Snapshot snapshot() { return new Snapshot(state, position, 90, volume, muted); }
        public void close() { closed = true; }
    }

    @Test public void renderingBoundsMuteAndEventsSurviveJavaCalls() throws Exception {
        Player p = new Player();
        DesktopRenderingControl control = new DesktopRenderingControl(p);
        control.refresh();
        assertNotNull(control.getLastChange().getEventedValue(id, RenderingControlVariable.Volume.class));
        control.getLastChange().reset();
        control.refresh();
        assertNull(control.getLastChange().getEventedValue(id, RenderingControlVariable.Volume.class));
        control.setMute(id, "Master", true);
        assertTrue(control.getMute(id, "Master"));
        assertNotNull(control.getLastChange().getEventedValue(id, RenderingControlVariable.Mute.class));
        control.setVolume(id, "Master", new UnsignedIntegerTwoBytes(0));
        assertEquals(0, p.volume);
        control.setVolume(id, "Master", new UnsignedIntegerTwoBytes(100));
        assertEquals(100, p.volume);
        p.volume = 150;
        assertEquals(100L, control.getVolume(id, "Master").getValue().longValue());
        p.volume = -10;
        assertEquals(0L, control.getVolume(id, "Master").getValue().longValue());
        assertEquals(601, assertThrows(RenderingControlException.class,
                () -> control.setVolume(id, "Master", new UnsignedIntegerTwoBytes(101))).getErrorCode());
        assertEquals(-10, p.volume);
    }

    @Test public void renderingValidatesInstanceBeforeNullableChannel() {
        DesktopRenderingControl control = new DesktopRenderingControl(new Player());
        assertEquals(702, assertThrows(RenderingControlException.class,
                () -> control.getMute(new UnsignedIntegerFourBytes(1), null)).getErrorCode());
        assertEquals(402, assertThrows(RenderingControlException.class,
                () -> control.getVolume(id, null)).getErrorCode());
        assertEquals(402, assertThrows(RenderingControlException.class,
                () -> control.setMute(id, "Left", true)).getErrorCode());
    }

    @Test public void transportMetadataLimitsAndErrorsRetainOrdering() throws Exception {
        Player p = new Player();
        DesktopAvTransport av = new DesktopAvTransport(p);
        assertEquals(716, assertThrows(AVTransportException.class,
                () -> av.setAVTransportURI(id, null, null)).getErrorCode());
        av.setAVTransportURI(id, "https://example.test/first", "x".repeat(262144));
        assertEquals(262144, av.getMediaInfo(id).getCurrentURIMetaData().length());
        assertEquals(402, assertThrows(AVTransportException.class,
                () -> av.setAVTransportURI(id, "https://example.test/second", "x".repeat(262145))).getErrorCode());
        assertEquals("https://example.test/first", av.getMediaInfo(id).getCurrentURI());
        av.setAVTransportURI(id, "https://example.test/second", null);
        assertEquals("", av.getMediaInfo(id).getCurrentURIMetaData());
        assertEquals(0, p.loads);
        assertEquals(717, assertThrows(AVTransportException.class, () -> av.play(id, null)).getErrorCode());
        assertEquals(712, assertThrows(AVTransportException.class, () -> av.setPlayMode(id, null)).getErrorCode());
        p.failStop = true;
        assertEquals(501, assertThrows(AVTransportException.class,
                () -> av.setAVTransportURI(id, "https://example.test/third", "")).getErrorCode());
        assertEquals("https://example.test/second", av.getMediaInfo(id).getCurrentURI());
    }

    @Test public void transportActionsPositionAndPublicationFollowState() throws Exception {
        Player p = new Player();
        DesktopAvTransport av = new DesktopAvTransport(p);
        assertEquals(0, av.getCurrentTransportActions(id).length);
        av.setAVTransportURI(id, "https://example.test/one", null);
        assertArrayEquals(new TransportAction[]{TransportAction.Play}, av.getCurrentTransportActions(id));
        av.getLastChange().reset();
        av.refresh();
        assertNull(av.getLastChange().getEventedValue(id, AVTransportVariable.TransportState.class));
        av.play(id, "1");
        assertNotNull(av.getLastChange().getEventedValue(id, AVTransportVariable.TransportState.class));
        assertArrayEquals(new TransportAction[]{TransportAction.Play, TransportAction.Pause,
                TransportAction.Stop, TransportAction.Seek}, av.getCurrentTransportActions(id));
        av.seek(id, "ABS_TIME", "00:00:12.500");
        assertEquals(12.5, p.position, 0);
        assertEquals("00:00:12", av.getPositionInfo(id).getRelTime());
        av.stop(id);
        assertEquals("00:00:00", av.getPositionInfo(id).getRelTime());
        av.setAVTransportURI(id, "https://example.test/two", "");
        assertEquals(1, p.loads);
        assertEquals(TransportState.STOPPED, av.getTransportInfo(id).getCurrentTransportState());
        assertNotNull(av.getLastChange().getEventedValue(id, AVTransportVariable.AVTransportURI.class));
        av.disable();
        assertTrue(p.closed);
        assertEquals(701, assertThrows(AVTransportException.class, () -> av.play(id, "1")).getErrorCode());
        assertEquals(718, assertThrows(AVTransportException.class,
                () -> av.play(new UnsignedIntegerFourBytes(1), "1")).getErrorCode());
    }

    @Test public void renderingBinderStillDiscoversAllFourActions() throws Exception {
        var service = new AnnotationLocalServiceBinder().read(DesktopRenderingControl.class);
        for (String action : new String[]{"GetVolume", "SetVolume", "GetMute", "SetMute"}) {
            assertNotNull(action, service.getAction(action));
        }
    }
}
