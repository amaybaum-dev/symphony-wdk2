package com.symphony.bdk.workflow;

import static com.symphony.bdk.workflow.custom.assertion.Assertions.assertThat;
import static com.symphony.bdk.workflow.custom.assertion.WorkflowAssert.content;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.symphony.bdk.core.service.message.model.Message;
import com.symphony.bdk.workflow.engine.ExecutionParameters;
import com.symphony.bdk.workflow.exception.NotFoundException;
import com.symphony.bdk.workflow.exception.UnauthorizedException;
import com.symphony.bdk.workflow.swadl.SwadlParser;
import com.symphony.bdk.workflow.swadl.v1.Workflow;

import com.github.fge.jsonschema.core.exceptions.ProcessingException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

class RequestReceivedEventIntegrationTest extends IntegrationTest {

  @Test
  void onRequestReceived() throws IOException, ProcessingException {
    final Workflow workflow =
        SwadlParser.fromYaml(getClass().getResourceAsStream("/event/request-received.swadl.yaml"));
    engine.deploy(workflow);

    engine.execute("request-received", new ExecutionParameters(Map.of("content", "Hello World!"), "myToken"));

    verify(messageService, timeout(5000).times(1)).send(eq("123"), content("Hello World!"));
  }

  @Test
  void onRequestReceived_inOneOf() throws IOException, ProcessingException {
    final Workflow workflow =
        SwadlParser.fromYaml(getClass().getResourceAsStream("/event/request-received-in-one-of.swadl.yaml"));

    engine.deploy(workflow);

    engine.execute("request-received-in-one-of", new ExecutionParameters(Map.of("content", "Hello World!"), "myToken"));

    verify(messageService, timeout(5000).times(1)).send(eq("123"), content("Hello World!"));

    engine.onEvent(messageReceived("/request-received-in-one-of"));

    verify(messageService, timeout(5000).times(1)).send(eq("123"), content("Hello World!"));
  }

  @Test
  void onRequestReceived_inOneOf_formRepliedSubsequentActivity() throws IOException, ProcessingException,
      InterruptedException {
    final Workflow workflow = SwadlParser.fromYaml(
        getClass().getResourceAsStream("/event/request-received-in-one-of-with-form-replied-activity.swadl.yaml"));

    when(messageService.send(anyString(), any(Message.class))).thenReturn(message("msgId"));

    engine.deploy(workflow);

    engine.execute("request-received-in-one-of-with-form-replied-activity",
        new ExecutionParameters(Map.of(), "myToken"));

    sleepToTimeout(1000);
    await().atMost(5, TimeUnit.SECONDS).ignoreExceptions().until(() -> {
      engine.onEvent(form("msgId", "formActivity", Collections.singletonMap("action", "one")));
      return true;
    });

    assertThat(workflow).executed("formActivity", "act");
  }

  @Test
  void onRequestReceived_badToken() throws IOException, ProcessingException {
    final Workflow workflow =
        SwadlParser.fromYaml(getClass().getResourceAsStream("/event/request-received.swadl.yaml"));

    engine.deploy(workflow);

    ExecutionParameters executionParameters = new ExecutionParameters(Map.of("content", "Hello World!"), "badToken");
    assertThatExceptionOfType(UnauthorizedException.class).isThrownBy(
            () -> engine.execute("request-received", executionParameters))
        .satisfies(e -> assertThat(e.getMessage()).isEqualTo("Request is not authorised"));
    verify(messageService, never()).send(anyString(), any(Message.class));
  }

  @Test
  void onRequestReceived_tokenNull() throws IOException, ProcessingException {
    final Workflow workflow =
        SwadlParser.fromYaml(getClass().getResourceAsStream("/event/request-received.swadl.yaml"));

    engine.deploy(workflow);

    ExecutionParameters executionParameters = new ExecutionParameters(Map.of("content", "Hello World!"), null);
    assertThatExceptionOfType(UnauthorizedException.class).isThrownBy(
            () -> engine.execute("request-received", executionParameters))
        .satisfies(e -> assertThat(e.getMessage()).isEqualTo("Request is not authorised"));
    verify(messageService, never()).send(anyString(), any(Message.class));
  }

  @Test
  void onRequestReceived_badWorkflowId() throws IOException, ProcessingException {
    final Workflow workflow =
        SwadlParser.fromYaml(getClass().getResourceAsStream("/event/request-received.swadl.yaml"));

    engine.deploy(workflow);

    ExecutionParameters executionParameters = new ExecutionParameters(Map.of("content", "Hello World!"), "myToken");
    assertThatExceptionOfType(NotFoundException.class).isThrownBy(
            () -> engine.execute("unfoundWorkflowId", executionParameters))
        .satisfies(e -> assertThat(e.getMessage()).isEqualTo("No workflow found with id unfoundWorkflowId"));
    verify(messageService, never()).send(anyString(), any(Message.class));
  }

  @Test
  void onRequestReceived_multipleWorkflows() throws IOException, ProcessingException {
    final Workflow workflow1 =
        SwadlParser.fromYaml(getClass().getResourceAsStream("/event/request-received.swadl.yaml"));
    engine.deploy(workflow1);

    final Workflow workflow2 =
        SwadlParser.fromYaml(getClass().getResourceAsStream("/event/request-received2.swadl.yaml"));
    engine.deploy(workflow2);

    // should only execute workflow1
    engine.execute("request-received", new ExecutionParameters(Map.of("content", "Hello World!"), "myToken"));

    assertThat(workflow1).isExecuted();
    verify(messageService, never()).send(anyString(), content("Second"));
  }

}
