/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.metrics.prometheus;

import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.MetricOptions;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.Gauge;
import org.apache.flink.metrics.Histogram;
import org.apache.flink.metrics.Meter;
import org.apache.flink.metrics.Metric;
import org.apache.flink.metrics.SimpleCounter;
import org.apache.flink.metrics.reporter.MetricReporter;
import org.apache.flink.metrics.util.TestMeter;
import org.apache.flink.runtime.metrics.MetricRegistry;
import org.apache.flink.runtime.metrics.MetricRegistryConfiguration;
import org.apache.flink.runtime.metrics.groups.FrontMetricGroup;
import org.apache.flink.runtime.metrics.groups.TaskManagerMetricGroup;
import org.apache.flink.runtime.metrics.util.TestingHistogram;
import org.apache.flink.util.TestLogger;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.util.Arrays;

import static org.apache.flink.metrics.prometheus.PrometheusReporter.ARG_PORT;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertThat;

/**
 * Basic test for {@link PrometheusReporter}.
 */
public class PrometheusReporterTest extends TestLogger {
	private static final int NON_DEFAULT_PORT = 9429;

	private static final String HOST_NAME = "hostname";
	private static final String TASK_MANAGER = "tm";

	private static final String HELP_PREFIX = "# HELP ";
	private static final String TYPE_PREFIX = "# TYPE ";
	private static final String DIMENSIONS = "host=\"" + HOST_NAME + "\",tm_id=\"" + TASK_MANAGER + "\"";
	private static final String DEFAULT_LABELS = "{" + DIMENSIONS + ",}";
	private static final String SCOPE_PREFIX = "flink_taskmanager_";

	@Rule
	public ExpectedException thrown = ExpectedException.none();

	private final MetricRegistry registry = new MetricRegistry(MetricRegistryConfiguration.fromConfiguration(createConfigWithOneReporter("test1", "" + NON_DEFAULT_PORT)));
	private final FrontMetricGroup<TaskManagerMetricGroup> metricGroup = new FrontMetricGroup<>(0, new TaskManagerMetricGroup(registry, HOST_NAME, TASK_MANAGER));
	private final MetricReporter reporter = registry.getReporters().get(0);

	/**
	 * {@link io.prometheus.client.Counter} may not decrease, so report {@link Counter} as {@link io.prometheus.client.Gauge}.
	 *
	 * @throws UnirestException Might be thrown on HTTP problems.
	 */
	@Test
	public void counterIsReportedAsPrometheusGauge() throws UnirestException {
		Counter testCounter = new SimpleCounter();
		testCounter.inc(7);

		assertThatGaugeIsExported(testCounter, "testCounter", "7.0");
	}

	@Test
	public void gaugeIsReportedAsPrometheusGauge() throws UnirestException {
		Gauge<Integer> testGauge = new Gauge<Integer>() {
			@Override
			public Integer getValue() {
				return 1;
			}
		};

		assertThatGaugeIsExported(testGauge, "testGauge", "1.0");
	}

	@Test
	public void nullGaugeDoesNotBreakReporter() throws UnirestException {
		Gauge<Integer> testGauge = new Gauge<Integer>() {
			@Override
			public Integer getValue() {
				return null;
			}
		};

		assertThatGaugeIsExported(testGauge, "testGauge", "0.0");
	}

	@Test
	public void meterRateIsReportedAsPrometheusGauge() throws UnirestException {
		Meter testMeter = new TestMeter();

		assertThatGaugeIsExported(testMeter, "testMeter", "5.0");
	}

	private void assertThatGaugeIsExported(Metric metric, String name, String expectedValue) throws UnirestException {
		final String prometheusName = SCOPE_PREFIX + name;
		assertThat(addMetricAndPollResponse(metric, name),
			containsString(HELP_PREFIX + prometheusName + " " + name + " (scope: taskmanager)\n" +
				TYPE_PREFIX + prometheusName + " gauge" + "\n" +
				prometheusName + DEFAULT_LABELS + " " + expectedValue + "\n"));
	}

	@Test
	public void histogramIsReportedAsPrometheusSummary() throws UnirestException {
		Histogram testHistogram = new TestingHistogram();

		String histogramName = "testHistogram";
		String summaryName = SCOPE_PREFIX + histogramName;

		String response = addMetricAndPollResponse(testHistogram, histogramName);
		assertThat(response, containsString(HELP_PREFIX + summaryName + " " + histogramName + " (scope: taskmanager)\n" +
			TYPE_PREFIX + summaryName + " summary" + "\n" +
			summaryName + "_count" + DEFAULT_LABELS + " 1.0" + "\n"));
		for (String quantile : Arrays.asList("0.5", "0.75", "0.95", "0.98", "0.99", "0.999")) {
			assertThat(response, containsString(
				summaryName + "{" + DIMENSIONS + ",quantile=\"" + quantile + "\",} " + quantile + "\n"));
		}
	}

	@Test
	public void endpointIsUnavailableAfterReporterIsClosed() throws UnirestException {
		reporter.close();
		thrown.expect(UnirestException.class);
		pollMetrics();
	}

	@Test
	public void invalidCharactersAreReplacedWithUnderscore() {
		assertThat(PrometheusReporter.replaceInvalidChars(""), equalTo(""));
		assertThat(PrometheusReporter.replaceInvalidChars("abc"), equalTo("abc"));
		assertThat(PrometheusReporter.replaceInvalidChars("abc\""), equalTo("abc_"));
		assertThat(PrometheusReporter.replaceInvalidChars("\"abc"), equalTo("_abc"));
		assertThat(PrometheusReporter.replaceInvalidChars("\"abc\""), equalTo("_abc_"));
		assertThat(PrometheusReporter.replaceInvalidChars("\"a\"b\"c\""), equalTo("_a_b_c_"));
		assertThat(PrometheusReporter.replaceInvalidChars("\"\"\"\""), equalTo("____"));
		assertThat(PrometheusReporter.replaceInvalidChars("    "), equalTo("____"));
		assertThat(PrometheusReporter.replaceInvalidChars("\"ab ;(c)'"), equalTo("_ab___c__"));
		assertThat(PrometheusReporter.replaceInvalidChars("a b c"), equalTo("a_b_c"));
		assertThat(PrometheusReporter.replaceInvalidChars("a b c "), equalTo("a_b_c_"));
		assertThat(PrometheusReporter.replaceInvalidChars("a;b'c*"), equalTo("a_b_c_"));
		assertThat(PrometheusReporter.replaceInvalidChars("a,=;:?'b,=;:?'c"), equalTo("a___:__b___:__c"));
	}

	@Test
	public void doubleGaugeIsConvertedCorrectly() {
		assertThat(PrometheusReporter.gaugeFrom(new Gauge<Double>() {
			@Override
			public Double getValue() {
				return 3.14;
			}
		}).get(), equalTo(3.14));
	}

	@Test
	public void shortGaugeIsConvertedCorrectly() {
		assertThat(PrometheusReporter.gaugeFrom(new Gauge<Short>() {
			@Override
			public Short getValue() {
				return 13;
			}
		}).get(), equalTo(13.));
	}

	@Test
	public void booleanGaugeIsConvertedCorrectly() {
		assertThat(PrometheusReporter.gaugeFrom(new Gauge<Boolean>() {
			@Override
			public Boolean getValue() {
				return true;
			}
		}).get(), equalTo(1.));
	}

	/**
	 * Prometheus only supports numbers, so report non-numeric gauges as 0.
	 */
	@Test
	public void stringGaugeCannotBeConverted() {
		assertThat(PrometheusReporter.gaugeFrom(new Gauge<String>() {
			@Override
			public String getValue() {
				return "I am not a number";
			}
		}).get(), equalTo(0.));
	}

	@Test
	public void registeringSameMetricTwiceDoesNotThrowException() {
		Counter counter = new SimpleCounter();
		counter.inc();
		String counterName = "testCounter";

		reporter.notifyOfAddedMetric(counter, counterName, metricGroup);
		reporter.notifyOfAddedMetric(counter, counterName, metricGroup);
	}

	@Test
	public void addingUnknownMetricTypeDoesNotThrowException(){
		class SomeMetricType implements Metric{}

		reporter.notifyOfAddedMetric(new SomeMetricType(), "name", metricGroup);
	}

	@Test
	public void cannotStartTwoReportersOnSamePort() {
		final MetricRegistry fixedPort1 = new MetricRegistry(MetricRegistryConfiguration.fromConfiguration(createConfigWithOneReporter("test1", "12345")));
		final MetricRegistry fixedPort2 = new MetricRegistry(MetricRegistryConfiguration.fromConfiguration(createConfigWithOneReporter("test2", "12345")));

		assertThat(fixedPort1.getReporters(), hasSize(1));
		assertThat(fixedPort2.getReporters(), hasSize(0));

		fixedPort1.shutdown();
		fixedPort2.shutdown();
	}

	@Test
	public void canStartTwoReportersWhenUsingPortRange() {
		final MetricRegistry portRange1 = new MetricRegistry(MetricRegistryConfiguration.fromConfiguration(createConfigWithOneReporter("test1", "9249-9252")));
		final MetricRegistry portRange2 = new MetricRegistry(MetricRegistryConfiguration.fromConfiguration(createConfigWithOneReporter("test2", "9249-9252")));

		assertThat(portRange1.getReporters(), hasSize(1));
		assertThat(portRange2.getReporters(), hasSize(1));

		portRange1.shutdown();
		portRange2.shutdown();
	}

	@Test
	public void cannotStartThreeReportersWhenPortRangeIsTooSmall() {
		final MetricRegistry smallPortRange1 = new MetricRegistry(MetricRegistryConfiguration.fromConfiguration(createConfigWithOneReporter("test1", "9253-9254")));
		final MetricRegistry smallPortRange2 = new MetricRegistry(MetricRegistryConfiguration.fromConfiguration(createConfigWithOneReporter("test2", "9253-9254")));
		final MetricRegistry smallPortRange3 = new MetricRegistry(MetricRegistryConfiguration.fromConfiguration(createConfigWithOneReporter("test3", "9253-9254")));

		assertThat(smallPortRange1.getReporters(), hasSize(1));
		assertThat(smallPortRange2.getReporters(), hasSize(1));
		assertThat(smallPortRange3.getReporters(), hasSize(0));

		smallPortRange1.shutdown();
		smallPortRange2.shutdown();
		smallPortRange3.shutdown();
	}

	private String addMetricAndPollResponse(Metric metric, String metricName) throws UnirestException {
		reporter.notifyOfAddedMetric(metric, metricName, metricGroup);
		return pollMetrics().getBody();
	}

	static HttpResponse<String> pollMetrics() throws UnirestException {
		return Unirest.get("http://localhost:" + NON_DEFAULT_PORT + "/metrics").asString();
	}

	static Configuration createConfigWithOneReporter(String reporterName, String portString) {
		Configuration cfg = new Configuration();
		cfg.setString(MetricOptions.REPORTERS_LIST, reporterName);
		cfg.setString(ConfigConstants.METRICS_REPORTER_PREFIX + reporterName + "." + ConfigConstants.METRICS_REPORTER_CLASS_SUFFIX, PrometheusReporter.class.getName());
		cfg.setString(ConfigConstants.METRICS_REPORTER_PREFIX + reporterName + "." + ARG_PORT, portString);
		return cfg;
	}

	@After
	public void closeReporterAndShutdownRegistry() {
		reporter.close();
		registry.shutdown();
	}
}
