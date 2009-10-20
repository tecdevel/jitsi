/*
 * SIP Communicator, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package net.java.sip.communicator.impl.neomedia;

import java.io.*;
import java.net.*;
import java.util.*;

import javax.media.*;
import javax.media.control.*;
import javax.media.format.*;
import javax.media.protocol.*;
import javax.media.rtp.*;
import javax.media.rtp.event.*;

import net.java.sip.communicator.impl.neomedia.device.*;
import net.java.sip.communicator.service.neomedia.*;
import net.java.sip.communicator.service.neomedia.device.*;
import net.java.sip.communicator.service.neomedia.format.*;
import net.java.sip.communicator.util.*;

/**
 * @author Lubomir Marinov
 */
public class MediaStreamImpl
    extends AbstractMediaStream
    implements ControllerListener,
               ReceiveStreamListener,
               SendStreamListener,
               SessionListener
{

    /**
     * The <tt>Logger</tt> used by the <tt>MediaStreamImpl</tt> class and its
     * instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(MediaStreamImpl.class);

    /**
     * The name of the property indicating the length of our receive buffer.
     */
    private static final String PROPERTY_NAME_RECEIVE_BUFFER_LENGTH
        = "net.java.sip.communicator.impl.media.RECEIVE_BUFFER_LENGTH";

    /**
     * The <tt>MediaDevice</tt> this instance uses for both capture and playback
     * of media.
     */
    private CaptureMediaDevice device;

    /**
     * The list of active <tt>Player</tt>s created during the operation of this
     * <tt>MediaStream</tt>.
     */
    protected final List<Player> players = new ArrayList<Player>();

    /**
     * The <tt>RTPConnector</tt> through which this instance sends and receives
     * RTP and RTCP traffic.
     */
    private final RTPConnectorImpl rtpConnector;

    /**
     * The <tt>RTPManager</tt> which utilizes {@link #rtpConnector} and sends
     * and receives RTP and RTCP traffic on behalf of this <tt>MediaStream</tt>.
     */
    private RTPManager rtpManager;

    /**
     * The <tt>MediaDirection</tt> in which this instance is started. For
     * example, {@link MediaDirection#SENDRECV} if this instances is both
     * sending and receiving data (e.g. RTP and RTCP) or
     * {@link MediaDirection#SENDONLY} if this instance is only sending data.
     */
    private MediaDirection startedDirection;

    /**
     * Initializes a new <tt>MediaStreamImpl</tt> instance which will use the
     * specified <tt>MediaDevice</tt> for both capture and playback of media
     * exchanged via the specified <tt>StreamConnector</tt>.
     *
     * @param connector the <tt>StreamConnector</tt> the new instance is to use
     * for sending and receiving media
     * @param device the <tt>MediaDevice</tt> the new instance is to use for
     * both capture and playback of media exchanged via the specified
     * <tt>StreamConnector</tt>
     */
    public MediaStreamImpl(StreamConnector connector, MediaDevice device)
    {

        /*
         * XXX Set the device early in order to make sure that its of the right
         * type because we do not support just about any MediaDevice yet.
         */
        setDevice(device);

        this.rtpConnector = new RTPConnectorImpl(connector);
    }

    /**
     * Releases the resources allocated by this instance in the course of its
     * execution and prepares it to be garbage collected.
     *
     * @see MediaStream#close()
     */
    public void close()
    {
        stop();

        rtpConnector.removeTargets();

        if (rtpManager != null)
        {
            rtpManager.removeReceiveStreamListener(this);
            rtpManager.removeSendStreamListener(this);
            rtpManager.removeSessionListener(this);
            rtpManager.dispose();
            rtpManager = null;
        }

        disposePlayers();

        getDevice().close();
    }

    /**
     * Notifies this <tt>ControllerListener</tt> that the <tt>Controller</tt>
     * which it is registered with has generated an event.
     *
     * @param event the <tt>ControllerEvent</tt> specifying the
     * <tt>Controller</tt> which is the source of the event and the very type of
     * the event
     * @see ControllerListener#controllerUpdate(ControllerEvent)
     */
    public void controllerUpdate(ControllerEvent event)
    {
        if (event instanceof RealizeCompleteEvent)
        {
            Player player = (Player) event.getSourceController();

            if (player != null)
            {
                player.start();

                realizeComplete(player);
            }
        }
    }

    /**
     * Creates new <tt>SendStream</tt> instances for the streams of
     * {@link #device} through {@link #rtpManager}.
     */
    private void createSendStreams()
    {
        RTPManager rtpManager = getRTPManager();
        DataSource dataSource = getDevice().getDataSource();
        int streamCount;

        if (dataSource instanceof PushBufferDataSource)
        {
            PushBufferStream[] streams
                = ((PushBufferDataSource) dataSource).getStreams();

            streamCount = (streams == null) ? 0 : streams.length;
        }
        else if (dataSource instanceof PushDataSource)
        {
            PushSourceStream[] streams
                = ((PushDataSource) dataSource).getStreams();

            streamCount = (streams == null) ? 0 : streams.length;
        }
        else if (dataSource instanceof PullBufferDataSource)
        {
            PullBufferStream[] streams
                = ((PullBufferDataSource) dataSource).getStreams();

            streamCount = (streams == null) ? 0 : streams.length;
        }
        else if (dataSource instanceof PullDataSource)
        {
            PullSourceStream[] streams
                = ((PullDataSource) dataSource).getStreams();

            streamCount = (streams == null) ? 0 : streams.length;
        }
        else
            streamCount = 1;

        for (int streamIndex = 0; streamIndex < streamCount; streamIndex++)
        {
            Throwable exception = null;

            try
            {
                rtpManager.createSendStream(dataSource, streamIndex);
            }
            catch (IOException ioe)
            {
                exception = ioe;
            }
            catch (UnsupportedFormatException ufe)
            {
                exception = ufe;
            }

            if (exception != null)
            {
                // TODO
                logger
                    .error(
                        "Failed to create send stream for data source "
                            + dataSource
                            + " and stream index "
                            + streamIndex,
                        exception);
            }
        }
    }

    /**
     * Releases the resources allocated by a specific <tt>Player</tt> in the
     * course of its execution and prepares it to be garbage collected.
     *
     * @param player the <tt>Player</tt> to dispose of
     */
    protected void disposePlayer(Player player)
    {
        player.stop();
        player.deallocate();
        player.close();

        players.remove(player);
    }

    /**
     * Releases the resources allocated by {@link #players} in the course of
     * their execution and prepares them to be garbage collected.
     */
    private void disposePlayers()
    {
        synchronized (players)
        {
            Player [] players
                = this.players.toArray(new Player[this.players.size()]);

            for (Player player : players)
                disposePlayer(player);
        }
    }

    /**
     * Gets the <tt>MediaDevice</tt> that this stream uses to play back and
     * capture media.
     *
     * @return the <tt>MediaDevice</tt> that this stream uses to play back and
     * capture media
     * @see MediaStream#getDevice()
     */
    public CaptureMediaDevice getDevice()
    {
        return device;
    }

    /**
     * Gets the <tt>MediaFormat</tt> that this stream is currently transmitting
     * in.
     *
     * @return the <tt>MediaFormat</tt> that this stream is currently
     * transmitting in
     * @see MediaStream#getFormat()
     */
    public MediaFormat getFormat()
    {
        return getDevice().getFormat();
    }

    /**
     * Gets the synchronization source (SSRC) identifier of the local peer or
     * <tt>null</tt> if it is not yet known.
     *
     * @return  the synchronization source (SSRC) identifier of the local peer
     * or <tt>null</tt> if it is not yet known
     * @see MediaStream#getLocalSourceID()
     */
    public String getLocalSourceID()
    {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * Gets the address that this stream is sending RTCP traffic to.
     *
     * @return an <tt>InetSocketAddress</tt> instance indicating the address
     * that this stream is sending RTCP traffic to
     * @see MediaStream#getRemoteControlAddress()
     */
    public InetSocketAddress getRemoteControlAddress()
    {
        return
            (InetSocketAddress)
                rtpConnector.getControlSocket().getRemoteSocketAddress();
    }

    /**
     * Gets the address that this stream is sending RTP traffic to.
     *
     * @return an <tt>InetSocketAddress</tt> instance indicating the address
     * that this stream is sending RTP traffic to
     * @see MediaStream#getRemoteDataAddress()
     */
    public InetSocketAddress getRemoteDataAddress()
    {
        return
            (InetSocketAddress)
                rtpConnector.getDataSocket().getRemoteSocketAddress();
    }

    /**
     * Get the synchronization source (SSRC) identifier of the remote peer or
     * <tt>null</tt> if it is not yet known.
     *
     * @return  the synchronization source (SSRC) identifier of the remote
     * peer or <tt>null</tt> if it is not yet known
     * @see MediaStream#getRemoteSourceID()
     */
    public String getRemoteSourceID()
    {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * Gets the <tt>RTPManager</tt> instance which sends and receives RTP and
     * RTCP traffic on behalf of this <tt>MediaStream</tt>.
     *
     * @return the <tt>RTPManager</tt> instance which sends and receives RTP and
     * RTCP traffic on behalf of this <tt>MediaStream</tt>
     */
    private RTPManager getRTPManager()
    {
        if (rtpManager == null)
        {
            rtpManager = RTPManager.newInstance();

            registerCustomCodecFormats(rtpManager);

            rtpManager.addReceiveStreamListener(this);
            rtpManager.addSendStreamListener(this);
            rtpManager.addSessionListener(this);

            /*
             * It appears that if we don't do this managers don't play. You can
             * try out some other buffer size to see if you can get better
             * smoothness.
             */
            BufferControl bc
                = (BufferControl)
                    rtpManager.getControl(BufferControl.class.getName());
            if (bc != null)
            {
                String buffStr
                    = NeomediaActivator
                        .getConfigurationService()
                            .getString(PROPERTY_NAME_RECEIVE_BUFFER_LENGTH);
                long buff = 100;

                try
                {
                    if ((buffStr != null) && (buffStr.length() > 0))
                        buff = Long.parseLong(buffStr);
                }
                catch (NumberFormatException nfe)
                {
                    logger
                        .warn(
                            buffStr
                                + " is not a valid receive buffer/long value",
                            nfe);
                }

                buff = bc.setBufferLength(buff);
                logger.trace("set receiver buffer len to " + buff);
                bc.setEnabledThreshold(true);
                bc.setMinimumThreshold(100);
            }

            rtpManager.initialize(rtpConnector);

            createSendStreams();
        }
        return rtpManager;
    }

    /**
     * Notifies this <tt>MediaStream</tt> that a specific <tt>Player</tt> of
     * remote content has generated a <tt>RealizeCompleteEvent</tt>. Allows
     * extenders to carry out additional processing on the <tt>Player</tt>.
     *
     * @param player the <tt>Player</tt> which is the source of a
     * <tt>RealizeCompleteEvent</tt>
     */
    protected void realizeComplete(Player player)
    {
    }

    /**
     * Registers any custom JMF <tt>Format</tt>s with a specific
     * <tt>RTPManager</tt>. Extenders should override in order to register their
     * own customizations.
     *
     * @param rtpManager the <tt>RTPManager</tt> to register any custom JMF
     * <tt>Format</tt>s with
     */
    protected void registerCustomCodecFormats(RTPManager rtpManager)
    {
    }

    /**
     * Sets the <tt>MediaDevice</tt> that this stream should use to play back
     * and capture media.
     *
     * @param device the <tt>MediaDevice</tt> that this stream should use to
     * play back and capture media
     * @see MediaStream#setDevice(MediaDevice)
     */
    public void setDevice(MediaDevice device)
    {
        this.device = (CaptureMediaDevice) device;
    }

    /**
     * Sets the <tt>MediaFormat</tt> that this <tt>MediaStream</tt> should
     * transmit in.
     *
     * @param format the <tt>MediaFormat</tt> that this <tt>MediaStream</tt>
     * should transmit in
     * @see MediaStream#setFormat(MediaFormat)
     */
    public void setFormat(MediaFormat format)
    {
        getDevice().setFormat(format);
    }

    /**
     * Sets the target of this <tt>MediaStream</tt> to which it is to send and
     * from which it is to receive data (e.g. RTP) and control data (e.g. RTCP).
     *
     * @param target the <tt>MediaStreamTarget</tt> describing the data
     * (e.g. RTP) and the control data (e.g. RTCP) locations to which this
     * <tt>MediaStream</tt> is to send and from which it is to receive
     * @see MediaStream#setTarget(MediaStreamTarget)
     */
    public void setTarget(MediaStreamTarget target)
    {
        rtpConnector.removeTargets();

        if (target != null)
        {
            InetSocketAddress dataAddr = target.getDataAddress();
            InetSocketAddress controlAddr = target.getControlAddress();

            try
            {
                rtpConnector
                    .addTarget(
                        new SessionAddress(
                                dataAddr.getAddress(),
                                dataAddr.getPort(),
                                controlAddr.getAddress(),
                                controlAddr.getPort()));
            }
            catch (IOException ioe)
            {
                // TODO
                logger.error("Failed to add target " + target, ioe);
            }
        }
    }

    /**
     * Starts capturing media from this stream's <tt>MediaDevice</tt> and then
     * streaming it through the local <tt>StreamConnector</tt> toward the
     * stream's target address and port. Also puts the <tt>MediaStream</tt> in a
     * listening state which make it play all media received from the
     * <tt>StreamConnector</tt> on the stream's <tt>MediaDevice</tt>.
     *
     * @see MediaStream#start()
     */
    public void start()
    {
        start(MediaDirection.SENDRECV);
    }

    /**
     * Starts the processing of media in this instance in a specific direction.
     *
     * @param direction a <tt>MediaDirection</tt> value which represents the
     * direction of the processing of media to be started. For example,
     * {@link MediaDirection#SENDRECV} to start both capture and playback of
     * media in this instance or {@link MediaDirection#SENDONLY} to only start
     * the capture of media in this instance
     */
    @SuppressWarnings("unchecked")
    public void start(MediaDirection direction)
    {
        if (direction == null)
            throw new IllegalArgumentException("direction");

        if ((MediaDirection.SENDRECV.equals(direction)
                    || MediaDirection.SENDONLY.equals(direction))
                && (!MediaDirection.SENDRECV.equals(startedDirection)
                        && !MediaDirection.SENDONLY.equals(startedDirection)))
        {
            RTPManager rtpManager = getRTPManager();
            Iterable<SendStream> sendStreams = rtpManager.getSendStreams();

            if (sendStreams != null)
                for (SendStream sendStream : sendStreams)
                    try
                    {
                        // TODO Are we sure we want to connect here?
                        sendStream.getDataSource().connect();
                        sendStream.start();
                    }
                    catch (IOException ioe)
                    {
                        logger
                            .warn("Failed to start stream " + sendStream, ioe);
                    }

            getDevice().start(MediaDirection.SENDONLY);

            if (MediaDirection.RECVONLY.equals(startedDirection))
                startedDirection = MediaDirection.SENDRECV;
            else if (startedDirection == null)
                startedDirection = MediaDirection.SENDONLY;
        }

        if ((MediaDirection.SENDRECV.equals(direction)
                    || MediaDirection.RECVONLY.equals(direction))
                && (!MediaDirection.SENDRECV.equals(startedDirection)
                        && !MediaDirection.RECVONLY.equals(startedDirection)))
        {
            RTPManager rtpManager = getRTPManager();
            Iterable<ReceiveStream> receiveStreams;

            try
            {
                receiveStreams = rtpManager.getReceiveStreams();
            }
            catch (Exception e)
            {
                /*
                 * it appears that in early call states, when there are no
                 * streams this method could throw a null pointer exception.
                 * Make sure we handle it gracefully
                 */
                logger.trace("Failed to retrieve receive streams", e);
                receiveStreams = null;
            }

            if (receiveStreams != null)
                for (ReceiveStream receiveStream : receiveStreams)
                    try
                    {
                        DataSource receiveStreamDataSource
                            = receiveStream.getDataSource();

                        /*
                         * For an unknown reason, the stream DataSource can be
                         * null at the end of the Call after re-INVITEs have
                         * been handled.
                         */
                        if (receiveStreamDataSource != null)
                            receiveStreamDataSource.start();
                    }
                    catch (IOException ioe)
                    {
                        logger
                            .warn(
                                "Failed to start stream " + receiveStream,
                                ioe);
                    }

            getDevice().start(MediaDirection.RECVONLY);

            if (MediaDirection.SENDONLY.equals(startedDirection))
                startedDirection = MediaDirection.SENDONLY;
            else if (startedDirection == null)
                startedDirection = MediaDirection.RECVONLY;
        }
    }

    /**
     * Stops all streaming and capturing in this <tt>MediaStream</tt> and closes
     * and releases all open/allocated devices/resources. Has no effect if this
     * <tt>MediaStream</tt> is already closed and is simply ignored.
     *
     * @see MediaStream#stop()
     */
    public void stop()
    {
        stop(MediaDirection.SENDRECV);
    }

    /**
     * Stops the processing of media in this instance in a specific direction.
     *
     * @param direction a <tt>MediaDirection</tt> value which represents the
     * direction of the processing of media to be stopped. For example,
     * {@link MediaDirection#SENDRECV} to stop both capture and playback of
     * media in this instance or {@link MediaDirection#SENDONLY} to only stop
     * the capture of media in this instance
     */
    @SuppressWarnings("unchecked")
    public void stop(MediaDirection direction)
    {
        if (direction == null)
            throw new IllegalArgumentException("direction");

        if (rtpManager == null)
            return;

        if ((MediaDirection.SENDRECV.equals(direction)
                    || MediaDirection.SENDONLY.equals(direction))
                && (MediaDirection.SENDRECV.equals(startedDirection)
                        || MediaDirection.SENDONLY.equals(startedDirection)))
        {
            Iterable<SendStream> sendStreams = rtpManager.getSendStreams();

            if (sendStreams != null)
                for (SendStream sendStream : sendStreams)
                    try
                    {
                        sendStream.getDataSource().stop();
                        sendStream.stop();

                        try
                        {
                            sendStream.close();
                        }
                        catch (NullPointerException npe)
                        {
                            /*
                             * Sometimes com.sun.media.rtp.RTCPTransmitter#bye()
                             * may throw NullPointerException but it does not
                             * seem to be guaranteed because it does not happen
                             * while debugging and stopping at a breakpoint on
                             * SendStream#close(). One of the cases in which it
                             * appears upon call hang-up is if we do not close
                             * the "old" SendStreams upon reinvite(s). Though we
                             * are now closing such SendStreams, ignore the
                             * exception here just in case because we already
                             * ignore IOExceptions.
                             */
                            logger
                                .error(
                                    "Failed to close stream " + sendStream,
                                    npe);
                        }
                    }
                    catch (IOException ioe)
                    {
                        logger.warn("Failed to stop stream " + sendStream, ioe);
                    }

            getDevice().stop(MediaDirection.SENDONLY);

            if (MediaDirection.SENDRECV.equals(startedDirection))
                startedDirection = MediaDirection.RECVONLY;
            else if (MediaDirection.SENDONLY.equals(startedDirection))
                startedDirection = null;
        }

        if ((MediaDirection.SENDRECV.equals(direction)
                || MediaDirection.RECVONLY.equals(direction))
            && (MediaDirection.SENDRECV.equals(startedDirection)
                    || MediaDirection.RECVONLY.equals(startedDirection)))
        {
            Iterable<ReceiveStream> receiveStreams;

            try
            {
                receiveStreams = rtpManager.getReceiveStreams();
            }
            catch (Exception e)
            {
                /*
                 * it appears that in early call states, when there are no
                 * streams this method could throw a null pointer exception.
                 * Make sure we handle it gracefully
                 */
                logger.trace("Failed to retrieve receive streams", e);
                receiveStreams = null;
            }

            if (receiveStreams != null)
                for (ReceiveStream receiveStream : receiveStreams)
                    try
                    {
                        DataSource receiveStreamDataSource
                            = receiveStream.getDataSource();

                        /*
                         * For an unknown reason, the stream DataSource can be
                         * null at the end of the Call after re-INVITEs have
                         * been handled.
                         */
                        if (receiveStreamDataSource != null)
                            receiveStreamDataSource.stop();
                    }
                    catch (IOException ioe)
                    {
                        logger
                            .warn(
                                "Failed to stop stream " + receiveStream,
                                ioe);
                    }

            getDevice().stop(MediaDirection.RECVONLY);

            if (MediaDirection.SENDRECV.equals(startedDirection))
                startedDirection = MediaDirection.SENDONLY;
            else if (MediaDirection.RECVONLY.equals(startedDirection))
                startedDirection = null;
        }
    }

    /**
     * Notifies this <tt>ReceiveStreamListener</tt> that the <tt>RTPManager</tt>
     * it is registered with has generated an event related to a <tt>ReceiveStream</tt>.
     *
     * @param event the <tt>ReceiveStreamEvent</tt> which specifies the
     * <tt>ReceiveStream</tt> that is the cause of the event and the very type
     * of the event
     * @see ReceiveStreamListener#update(ReceiveStreamEvent)
     */
    public void update(ReceiveStreamEvent event)
    {
        if (event instanceof NewReceiveStreamEvent)
        {
            ReceiveStream receiveStream = event.getReceiveStream();

            if (receiveStream != null)
            {
                DataSource receiveStreamDataSource
                    = receiveStream.getDataSource();

                if (receiveStreamDataSource != null)
                {
                    Player player = null;
                    Throwable exception = null;

                    try
                    {
                        player = Manager.createPlayer(receiveStreamDataSource);
                    }
                    catch (IOException ioe)
                    {
                        exception = ioe;
                    }
                    catch (NoPlayerException npe)
                    {
                        exception = npe;
                    }

                    if (exception != null)
                        logger
                            .error(
                                "Failed to create player for new receive stream "
                                    + receiveStream,
                                exception);
                    else
                    {
                        player.addControllerListener(this);
                        player.realize();

                        synchronized (players)
                        {
                            players.add(player);
                        }
                    }
                }
            }
        }
    }

    /**
     * Notifies this <tt>SendStreamListener</tt> that the <tt>RTPManager</tt> it
     * is registered with has generated an event related to a <tt>SendStream</tt>.
     *
     * @param event the <tt>SendStreamEvent</tt> which specifies the
     * <tt>SendStream</tt> that is the cause of the event and the very type of
     * the event
     * @see SendStreamListener#update(SendStreamEvent)
     */
    public void update(SendStreamEvent event)
    {
        // TODO Auto-generated method stub
    }


    /**
     * Notifies this <tt>SessionListener</tt> that the <tt>RTPManager</tt> it is
     * registered with has generated an event which pertains to the seesion as a
     * whole and does not belong to a <tt>ReceiveStream</tt> or a
     * <tt>SendStream</tt> or a remote participant necessarily.
     *
     * @param event the <tt>SessionEvent</tt> which specifies the source and the
     * very type of the event
     * @see SessionListener#update(SessionEvent)
     */
    public void update(SessionEvent event)
    {
        // TODO Auto-generated method stub
    }
}
