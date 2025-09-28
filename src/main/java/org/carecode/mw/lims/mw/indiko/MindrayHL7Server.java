package org.carecode.mw.lims.mw.indiko;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.app.Connection;
import ca.uhn.hl7v2.app.HL7Service;
import ca.uhn.hl7v2.app.Initiator;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v231.datatype.ST;
import ca.uhn.hl7v2.model.v231.message.ACK;
import ca.uhn.hl7v2.model.v231.message.ORU_R01;
import ca.uhn.hl7v2.model.v231.message.QRY_A19;
import ca.uhn.hl7v2.model.v231.segment.MSH;
import ca.uhn.hl7v2.model.v231.segment.OBR;
import ca.uhn.hl7v2.model.v231.segment.OBX;
import ca.uhn.hl7v2.model.v231.segment.PID;
import ca.uhn.hl7v2.model.v231.segment.QRD;
import ca.uhn.hl7v2.protocol.ReceivingApplication;
import ca.uhn.hl7v2.protocol.ReceivingApplicationException;
import ca.uhn.hl7v2.util.Terser;
import java.io.IOException;
import java.net.ServerSocket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.carecode.lims.libraries.DataBundle;
import org.carecode.lims.libraries.OrderRecord;
import org.carecode.lims.libraries.PatientRecord;
import org.carecode.lims.libraries.QueryRecord;
import org.carecode.lims.libraries.ResultsRecord;

public class MindrayHL7Server {

    private static final Logger logger = LogManager.getLogger(MindrayHL7Server.class);

    private HapiContext context;
    private HL7Service hl7Service;
    private static int port;
    private DataBundle patientDataBundle = new DataBundle();

    public void start(int port) {
        MindrayHL7Server.port = port;
        try {
            context = new DefaultHapiContext();

            // Create HL7 service for listening
            hl7Service = context.newServer(port, false);

            // Register application for handling different message types
            hl7Service.registerApplication("QRY", "A19", new QueryMessageHandler());
            hl7Service.registerApplication("ORU", "R01", new ResultMessageHandler());

            hl7Service.startAndWait();
            logger.info("MindrayCL1000i HL7 Server started on port " + port);

        } catch (Exception e) {
            logger.error("Error starting HL7 server on port " + port, e);
        }
    }

    public void stop() {
        try {
            if (hl7Service != null) {
                hl7Service.stopAndWait();
                logger.info("HL7 Server stopped.");
            }
            if (context != null) {
                context.close();
            }
        } catch (IOException e) {
            logger.error("Error stopping HL7 server", e);
        }
    }

    public static void restartServer() {
        try {
            logger.info("Restarting HL7 server on port " + port + "...");
            Thread.sleep(2000);

            MindrayHL7Server server = new MindrayHL7Server();
            server.start(port);
            logger.info("HL7 Server restarted successfully.");

        } catch (InterruptedException e) {
            logger.error("Error while restarting the HL7 server", e);
            Thread.currentThread().interrupt();
        }
    }

    // Handler for Query messages (QRY^A19)
    private class QueryMessageHandler implements ReceivingApplication {

        @Override
        public Message processMessage(Message message, Map<String, Object> metadata)
                throws ReceivingApplicationException {
            try {
                logger.info("Received Query message: " + message.toString());

                if (message instanceof QRY_A19) {
                    QRY_A19 queryMsg = (QRY_A19) message;

                    // Extract sample ID from QRD segment
                    QRD qrd = queryMsg.getQRD();
                    String sampleId = extractSampleIdFromQuery(qrd);
                    logger.info("Sample ID extracted from query: " + sampleId);

                    // Create query record for LIS
                    QueryRecord queryRecord = new QueryRecord(0, sampleId, sampleId, "");

                    // Get test orders from LIS
                    DataBundle dataBundle = LISCommunicator.pullTestOrdersForSampleRequests(queryRecord);

                    if (dataBundle != null && !dataBundle.getOrderRecords().isEmpty()) {
                        // Send back test order information as ORU^R01
                        return createOrderResponseMessage(queryMsg, dataBundle);
                    } else {
                        // Send ACK with no data found
                        return createAckMessage(queryMsg, "No orders found for sample: " + sampleId);
                    }
                }

                return createAckMessage(message, "Query processed");

            } catch (Exception e) {
                logger.error("Error processing query message", e);
                throw new ReceivingApplicationException("Error processing query", e);
            }
        }

        @Override
        public boolean canProcess(Message message) {
            return message instanceof QRY_A19;
        }
    }

    // Handler for Result messages (ORU^R01)
    private class ResultMessageHandler implements ReceivingApplication {

        @Override
        public Message processMessage(Message message, Map<String, Object> metadata)
                throws ReceivingApplicationException {
            try {
                logger.info("Received Result message: " + message.toString());

                if (message instanceof ORU_R01) {
                    ORU_R01 resultMsg = (ORU_R01) message;

                    // Parse result message and extract data
                    DataBundle dataBundle = parseResultMessage(resultMsg);

                    // Send results to LIS
                    LISCommunicator.pushResults(dataBundle);

                    // Send ACK response
                    return createAckMessage(resultMsg, "Results processed successfully");
                }

                return createAckMessage(message, "Result processed");

            } catch (Exception e) {
                logger.error("Error processing result message", e);
                throw new ReceivingApplicationException("Error processing result", e);
            }
        }

        @Override
        public boolean canProcess(Message message) {
            return message instanceof ORU_R01;
        }
    }

    private String extractSampleIdFromQuery(QRD qrd) throws Exception {
        // Extract sample ID from QRD segment
        // QRD-8 Who Subject Filter typically contains sample ID
        ST sampleIdField = qrd.getWhoSubjectFilter(0);
        return sampleIdField != null ? sampleIdField.getValue() : "";
    }

    private DataBundle parseResultMessage(ORU_R01 resultMsg) throws Exception {
        DataBundle dataBundle = new DataBundle();

        // Parse PID segment for patient information
        PID pid = resultMsg.getPATIENT_RESULT().getPATIENT().getPID();
        PatientRecord patientRecord = parsePatientFromPID(pid);
        dataBundle.setPatientRecord(patientRecord);

        // Parse OBR and OBX segments for results
        var patientResult = resultMsg.getPATIENT_RESULT();
        var orderObservation = patientResult.getORDER_OBSERVATION();

        OBR obr = orderObservation.getOBR();
        String sampleId = obr.getFillerOrderNumber().getEntityIdentifier().getValue();

        // Process OBX segments for individual results
        int obsCount = orderObservation.getOBSERVATIONReps();
        for (int i = 0; i < obsCount; i++) {
            OBX obx = orderObservation.getOBSERVATION(i).getOBX();
            ResultsRecord resultRecord = parseResultFromOBX(obx, sampleId);
            if (resultRecord != null) {
                dataBundle.getResultsRecords().add(resultRecord);
            }
        }

        return dataBundle;
    }

    private PatientRecord parsePatientFromPID(PID pid) throws Exception {
        String patientId = pid.getPatientIdentifierList(0).getID().getValue();
        String patientName = pid.getPatientName(0).getFamilyName().getSurname().getValue() + " " +
                           pid.getPatientName(0).getGivenName().getValue();
        String sex = pid.getSex().getValue();
        String dob = pid.getDateTimeOfBirth().getTimeOfAnEvent().getValue();

        return new PatientRecord(0, patientId, "", patientName, "", sex, "", dob, "", "", "");
    }

    private ResultsRecord parseResultFromOBX(OBX obx, String sampleId) throws Exception {
        String testCode = obx.getObservationIdentifier().getIdentifier().getValue();
        String resultValue = obx.getObservationValue(0).getData().toString();
        String units = obx.getUnits().getIdentifier().getValue();
        String dateTime = obx.getDateTimeOfTheObservation().getTimeOfAnEvent().getValue();

        return new ResultsRecord(0, testCode, resultValue, units, dateTime, "MindrayCL1000i", sampleId);
    }

    private ORU_R01 createOrderResponseMessage(QRY_A19 queryMsg, DataBundle dataBundle) throws Exception {
        ORU_R01 response = new ORU_R01();

        // Populate MSH segment
        MSH msh = response.getMSH();
        populateMSH(msh, "ORU", "R01");

        // Populate PID segment with patient data
        if (dataBundle.getPatientRecord() != null) {
            PID pid = response.getPATIENT_RESULT().getPATIENT().getPID();
            populatePID(pid, dataBundle.getPatientRecord());
        }

        // Populate OBR segment with order information
        if (!dataBundle.getOrderRecords().isEmpty()) {
            OBR obr = response.getPATIENT_RESULT().getORDER_OBSERVATION().getOBR();
            populateOBR(obr, dataBundle.getOrderRecords().get(0));
        }

        return response;
    }

    private ACK createAckMessage(Message originalMsg, String ackText) throws Exception {
        ACK ack = new ACK();

        // Populate MSH segment
        MSH msh = ack.getMSH();
        populateMSH(msh, "ACK", "");

        // Set acknowledgment text
        ack.getMSA().getAcknowledgementCode().setValue("AA"); // Application Accept
        ack.getMSA().getTextMessage().setValue(ackText);

        return ack;
    }

    private void populateMSH(MSH msh, String messageType, String triggerEvent) throws Exception {
        msh.getFieldSeparator().setValue("|");
        msh.getEncodingCharacters().setValue("^~\\&");
        msh.getSendingApplication().getNamespaceID().setValue("MindrayCL1000i");
        msh.getSendingFacility().getNamespaceID().setValue("Laboratory");
        msh.getReceivingApplication().getNamespaceID().setValue("LIS");
        msh.getReceivingFacility().getNamespaceID().setValue("Hospital");
        msh.getDateTimeOfMessage().getTimeOfAnEvent().setValue(getCurrentDateTime());
        msh.getMessageType().getMessageType().setValue(messageType);
        msh.getMessageType().getTriggerEvent().setValue(triggerEvent);
        msh.getMessageControlID().setValue(String.valueOf(System.currentTimeMillis()));
        msh.getProcessingID().getProcessingID().setValue("P");
        msh.getVersionID().getVersionID().setValue("2.3.1");
    }

    private void populatePID(PID pid, PatientRecord patient) throws Exception {
        pid.getPatientIdentifierList(0).getID().setValue(patient.getPatientId());
        pid.getPatientName(0).getFamilyName().getSurname().setValue(patient.getPatientName());
        pid.getPatientName(0).getGivenName().setValue(patient.getPatientSecondName());
        pid.getSex().setValue(patient.getPatientSex());
        if (patient.getDob() != null && !patient.getDob().isEmpty()) {
            pid.getDateTimeOfBirth().getTimeOfAnEvent().setValue(patient.getDob());
        }
    }

    private void populateOBR(OBR obr, OrderRecord order) throws Exception {
        obr.getSetIDOBR().setValue("1");
        obr.getFillerOrderNumber().getEntityIdentifier().setValue(order.getSampleId());
        obr.getUniversalServiceIdentifier().getIdentifier().setValue("PANEL");
        obr.getObservationDateTime().getTimeOfAnEvent().setValue(getCurrentDateTime());
        obr.getOrderingProvider(0).getIDNumber().setValue("Laboratory");
    }

    private String getCurrentDateTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        return sdf.format(new Date());
    }
}