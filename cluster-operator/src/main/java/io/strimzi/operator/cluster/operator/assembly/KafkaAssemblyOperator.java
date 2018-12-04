/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.openshift.api.model.Route;
import io.strimzi.api.kafka.KafkaAssemblyList;
import io.strimzi.api.kafka.model.DoneableKafka;
import io.strimzi.api.kafka.model.ExternalLogging;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.CertificateAuthority;
import io.strimzi.certs.CertManager;
import io.strimzi.operator.cluster.model.AbstractModel;
import io.strimzi.operator.cluster.model.Ca;
import io.strimzi.operator.cluster.model.ClientsCa;
import io.strimzi.operator.cluster.model.ClusterCa;
import io.strimzi.operator.cluster.model.EntityOperator;
import io.strimzi.operator.cluster.model.EntityTopicOperator;
import io.strimzi.operator.cluster.model.EntityUserOperator;
import io.strimzi.operator.cluster.model.KafkaCluster;
import io.strimzi.operator.cluster.model.ModelUtils;
import io.strimzi.operator.cluster.model.TopicOperator;
import io.strimzi.operator.cluster.model.ZookeeperCluster;
import io.strimzi.operator.cluster.operator.resource.KafkaSetOperator;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.cluster.operator.resource.StatefulSetOperator;
import io.strimzi.operator.cluster.operator.resource.ZookeeperSetOperator;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.Util;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.model.ResourceType;
import io.strimzi.operator.common.operator.resource.ClusterRoleBindingOperator;
import io.strimzi.operator.common.operator.resource.ConfigMapOperator;
import io.strimzi.operator.common.operator.resource.DeploymentOperator;
import io.strimzi.operator.common.operator.resource.PvcOperator;
import io.strimzi.operator.common.operator.resource.ReconcileResult;
import io.strimzi.operator.common.operator.resource.RoleBindingOperator;
import io.strimzi.operator.common.operator.resource.RouteOperator;
import io.strimzi.operator.common.operator.resource.ServiceAccountOperator;
import io.strimzi.operator.common.operator.resource.ServiceOperator;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BiFunction;

import static io.strimzi.operator.common.operator.resource.AbstractScalableResourceOperator.STRIMZI_OPERATOR_DOMAIN;

/**
 * <p>Assembly operator for a "Kafka" assembly, which manages:</p>
 * <ul>
 *     <li>A ZooKeeper cluster StatefulSet and related Services</li>
 *     <li>A Kafka cluster StatefulSet and related Services</li>
 *     <li>Optionally, a TopicOperator Deployment</li>
 * </ul>
 */
public class KafkaAssemblyOperator extends AbstractAssemblyOperator<KubernetesClient, Kafka, KafkaAssemblyList, DoneableKafka, Resource<Kafka, DoneableKafka>> {
    private static final Logger log = LogManager.getLogger(KafkaAssemblyOperator.class.getName());

    private final long operationTimeoutMs;

    private final ZookeeperSetOperator zkSetOperations;
    private final KafkaSetOperator kafkaSetOperations;
    private final ServiceOperator serviceOperations;
    private final RouteOperator routeOperations;
    private final PvcOperator pvcOperations;
    private final DeploymentOperator deploymentOperations;
    private final ConfigMapOperator configMapOperations;
    private final ServiceAccountOperator serviceAccountOperator;
    private final RoleBindingOperator roleBindingOperator;
    private final ClusterRoleBindingOperator clusterRoleBindingOperator;

    public static final String ANNOTATION_MANUAL_RESTART = STRIMZI_OPERATOR_DOMAIN + "/manual-rolling-update";

    /**
     * @param vertx The Vertx instance
     * @param isOpenShift Whether we're running with OpenShift
     */
    public KafkaAssemblyOperator(Vertx vertx, boolean isOpenShift,
                                 long operationTimeoutMs,
                                 CertManager certManager,
                                 ResourceOperatorSupplier supplier) {
        super(vertx, isOpenShift, ResourceType.KAFKA, certManager, supplier.kafkaOperator, supplier.secretOperations, supplier.networkPolicyOperator);
        this.operationTimeoutMs = operationTimeoutMs;
        this.serviceOperations = supplier.serviceOperations;
        this.routeOperations = supplier.routeOperations;
        this.zkSetOperations = supplier.zkSetOperations;
        this.kafkaSetOperations = supplier.kafkaSetOperations;
        this.configMapOperations = supplier.configMapOperations;
        this.pvcOperations = supplier.pvcOperations;
        this.deploymentOperations = supplier.deploymentOperations;
        this.serviceAccountOperator = supplier.serviceAccountOperator;
        this.roleBindingOperator = supplier.roleBindingOperator;
        this.clusterRoleBindingOperator = supplier.clusterRoleBindingOperator;
    }

    @Override
    public Future<Void> createOrUpdate(Reconciliation reconciliation, Kafka kafkaAssembly) {
        Future<Void> chainFuture = Future.future();
        if (kafkaAssembly.getSpec() == null) {
            log.error("{} spec cannot be null", kafkaAssembly.getMetadata().getName());
            return Future.failedFuture("Spec cannot be null");
        }
        new ReconciliationState(reconciliation, kafkaAssembly)
                .reconcileCas()

                .compose(state -> state.zkManualPodCleaning())
                .compose(state -> state.zkManualRollingUpdate())
                .compose(state -> state.getZookeeperState())
                .compose(state -> state.zkScaleDown())
                .compose(state -> state.zkService())
                .compose(state -> state.zkHeadlessService())
                .compose(state -> state.zkAncillaryCm())
                .compose(state -> state.zkNodesSecret())
                .compose(state -> state.zkNetPolicy())
                .compose(state -> state.zkStatefulSet())
                .compose(state -> state.zkRollingUpdate())
                .compose(state -> state.zkScaleUp())
                .compose(state -> state.zkServiceEndpointReadiness())
                .compose(state -> state.zkHeadlessServiceEndpointReadiness())

                .compose(state -> state.kafkaManualPodCleaning())
                .compose(state -> state.kafkaManualRollingUpdate())
                .compose(state -> state.getKafkaClusterDescription())
                .compose(state -> state.kafkaInitServiceAccount())
                .compose(state -> state.kafkaInitClusterRoleBinding())
                .compose(state -> state.kafkaScaleDown())
                .compose(state -> state.kafkaService())
                .compose(state -> state.kafkaHeadlessService())
                .compose(state -> state.kafkaExternalBootstrapService())
                .compose(state -> state.kafkaReplicaServices())
                .compose(state -> state.kafkaBootstrapRoute())
                .compose(state -> state.kafkaReplicaRoutes())
                .compose(state -> state.kafkaExternalBootstrapServiceReady())
                .compose(state -> state.kafkaReplicaServicesReady())
                .compose(state -> state.kafkaBootstrapRouteReady())
                .compose(state -> state.kafkaReplicaRoutesReady())
                .compose(state -> state.kafkaGenerateCertificates())
                .compose(state -> state.kafkaAncillaryCm())
                .compose(state -> state.kafkaBrokersSecret())
                .compose(state -> state.kafkaNetPolicy())
                .compose(state -> state.kafkaStatefulSet())
                .compose(state -> state.kafkaRollingUpdate())
                .compose(state -> state.kafkaScaleUp())
                .compose(state -> state.kafkaServiceEndpointReady())
                .compose(state -> state.kafkaHeadlessServiceEndpointReady())

                .compose(state -> state.getTopicOperatorDescription())
                .compose(state -> state.topicOperatorServiceAccount())
                .compose(state -> state.topicOperatorRoleBinding())
                .compose(state -> state.topicOperatorAncillaryCm())
                .compose(state -> state.topicOperatorSecret())
                .compose(state -> state.topicOperatorDeployment())

                .compose(state -> state.getEntityOperatorDescription())
                .compose(state -> state.entityOperatorServiceAccount())
                .compose(state -> state.entityOperatorTopicOpRoleBinding())
                .compose(state -> state.entityOperatorUserOpRoleBinding())
                .compose(state -> state.entityOperatorTopicOpAncillaryCm())
                .compose(state -> state.entityOperatorUserOpAncillaryCm())
                .compose(state -> state.entityOperatorSecret())
                .compose(state -> state.entityOperatorDeployment())

                .compose(state -> chainFuture.complete(), chainFuture);

        return chainFuture;
    }

    /**
     * Hold the mutable state during a reconciliation
     */
    class ReconciliationState {

        private final String namespace;
        private final String name;
        private final Kafka kafkaAssembly;
        private final Reconciliation reconciliation;

        private ClusterCa clusterCa;
        private ClientsCa clientsCa;

        private ZookeeperCluster zkCluster;
        private Service zkService;
        private Service zkHeadlessService;
        private ConfigMap zkMetricsAndLogsConfigMap;
        private ReconcileResult<StatefulSet> zkDiffs;
        private boolean zkAncillaryCmChange;

        private KafkaCluster kafkaCluster = null;
        private Service kafkaService;
        private Service kafkaHeadlessService;
        private ConfigMap kafkaMetricsAndLogsConfigMap;
        private ReconcileResult<StatefulSet> kafkaDiffs;
        private String kafkaExternalBootstrapDnsName = null;
        private SortedMap<Integer, String> kafkaExternalAddresses = new TreeMap<>();
        private SortedMap<Integer, String> kafkaExternalDnsNames = new TreeMap<>();
        private boolean kafkaAncillaryCmChange;

        private TopicOperator topicOperator;
        private Deployment toDeployment = null;
        private ConfigMap toMetricsAndLogsConfigMap = null;

        private EntityOperator entityOperator;
        private Deployment eoDeployment = null;
        private ConfigMap topicOperatorMetricsAndLogsConfigMap = null;
        private ConfigMap userOperatorMetricsAndLogsConfigMap;

        ReconciliationState(Reconciliation reconciliation, Kafka kafkaAssembly) {
            this.reconciliation = reconciliation;
            this.kafkaAssembly = kafkaAssembly;
            this.namespace = kafkaAssembly.getMetadata().getNamespace();
            this.name = kafkaAssembly.getMetadata().getName();
        }

        /**
         * Asynchronously reconciles the cluster and clients CA secrets.
         * The cluster CA secret has to have the name determined by {@link AbstractModel#clusterCaCertSecretName(String)}.
         * The clients CA secret has to have the name determined by {@link KafkaCluster#clientsCaCertSecretName(String)}.
         * Within both the secrets the current certificate is stored under the key {@code ca.crt}
         * and the current key is stored under the key {@code ca.key}.
         */
        Future<ReconciliationState> reconcileCas() {
            Labels caLabels = Labels.userLabels(kafkaAssembly.getMetadata().getLabels()).withKind(reconciliation.type().toString()).withCluster(reconciliation.name());
            Future<ReconciliationState> result = Future.future();
            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").<ReconciliationState>executeBlocking(
                future -> {
                    try {
                        String clusterCaCertName = AbstractModel.clusterCaCertSecretName(name);
                        String clusterCaKeyName = AbstractModel.clusterCaKeySecretName(name);
                        String clientsCaCertName = KafkaCluster.clientsCaCertSecretName(name);
                        String clientsCaKeyName = KafkaCluster.clientsCaKeySecretName(name);
                        Secret clusterCaCertSecret = null;
                        Secret clusterCaKeySecret = null;
                        Secret clientsCaCertSecret = null;
                        Secret clientsCaKeySecret = null;
                        List<Secret> clusterSecrets = secretOperations.list(reconciliation.namespace(), caLabels);
                        for (Secret secret : clusterSecrets) {
                            String secretName = secret.getMetadata().getName();
                            if (secretName.equals(clusterCaCertName)) {
                                clusterCaCertSecret = secret;
                            } else if (secretName.equals(clusterCaKeyName)) {
                                clusterCaKeySecret = secret;
                            } else if (secretName.equals(clientsCaCertName)) {
                                clientsCaCertSecret = secret;
                            } else if (secretName.equals(clientsCaKeyName)) {
                                clientsCaKeySecret = secret;
                            }
                        }
                        OwnerReference ownerRef = new OwnerReferenceBuilder()
                                .withApiVersion(kafkaAssembly.getApiVersion())
                                .withKind(kafkaAssembly.getKind())
                                .withName(kafkaAssembly.getMetadata().getName())
                                .withUid(kafkaAssembly.getMetadata().getUid())
                                .withBlockOwnerDeletion(false)
                                .withController(false)
                                .build();

                        CertificateAuthority clusterCaConfig = kafkaAssembly.getSpec().getClusterCa();
                        this.clusterCa = new ClusterCa(certManager, name, clusterCaCertSecret, clusterCaKeySecret,
                                ModelUtils.getCertificateValidity(clusterCaConfig),
                                ModelUtils.getRenewalDays(clusterCaConfig),
                                clusterCaConfig == null || clusterCaConfig.isGenerateCertificateAuthority());
                        clusterCa.createOrRenew(
                                reconciliation.namespace(), reconciliation.name(), caLabels.toMap(),
                                ownerRef);

                        this.clusterCa.initCaSecrets(clusterSecrets);

                        CertificateAuthority clientsCaConfig = kafkaAssembly.getSpec().getClientsCa();
                        this.clientsCa = new ClientsCa(certManager,
                                clientsCaCertName, clientsCaCertSecret,
                                clientsCaKeyName, clientsCaKeySecret,
                                ModelUtils.getCertificateValidity(clientsCaConfig),
                                ModelUtils.getRenewalDays(clientsCaConfig),
                                clientsCaConfig == null || clientsCaConfig.isGenerateCertificateAuthority());
                        clientsCa.createOrRenew(reconciliation.namespace(), reconciliation.name(),
                                caLabels.toMap(), ownerRef);

                        secretOperations.reconcile(reconciliation.namespace(), clusterCaCertName, this.clusterCa.caCertSecret())
                                .compose(ignored -> secretOperations.reconcile(reconciliation.namespace(), clusterCaKeyName, this.clusterCa.caKeySecret()))
                                .compose(ignored -> secretOperations.reconcile(reconciliation.namespace(), clientsCaCertName, this.clientsCa.caCertSecret()))
                                .compose(ignored -> secretOperations.reconcile(reconciliation.namespace(), clientsCaKeyName, this.clientsCa.caKeySecret()))
                                .compose(ignored  -> {
                                    future.complete(this);
                                }, future);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                }, true,
                    result.completer()
            );
            return result;
        }

        Future<ReconciliationState> kafkaManualRollingUpdate() {
            Future<StatefulSet> futss = kafkaSetOperations.getAsync(namespace, KafkaCluster.kafkaClusterName(name));
            if (futss != null) {
                return futss.compose(ss -> {
                    if (ss != null) {
                        String value = Util.annotations(ss).get(ANNOTATION_MANUAL_RESTART);
                        if (value != null && value.equals("true")) {
                            return kafkaSetOperations.maybeRollingUpdate(ss, pod -> {

                                log.debug("{}: Rolling Kafka pod {} due to manual rolling update",
                                        reconciliation, pod.getMetadata().getName());
                                return true;
                            });
                        }
                    }
                    return Future.succeededFuture();
                }).map(i -> this);
            }
            return Future.succeededFuture(this);
        }

        Future<ReconciliationState> zkManualRollingUpdate() {
            Future<StatefulSet> futss = zkSetOperations.getAsync(namespace, ZookeeperCluster.zookeeperClusterName(name));
            if (futss != null) {
                return futss.compose(ss -> {
                    if (ss != null) {
                        String value = Util.annotations(ss).get(ANNOTATION_MANUAL_RESTART);
                        if (value != null && value.equals("true")) {

                            return zkSetOperations.maybeRollingUpdate(ss, pod -> {

                                log.debug("{}: Rolling Zookeeper pod {} to manual rolling update",
                                        reconciliation, pod.getMetadata().getName());
                                return true;
                            });
                        }
                    }
                    return Future.succeededFuture();
                }).map(i -> this);
            }
            return Future.succeededFuture(this);
        }

        Future<ReconciliationState> getZookeeperState() {
            Future<ReconciliationState> fut = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
                future -> {
                    try {
                        this.zkCluster = ZookeeperCluster.fromCrd(kafkaAssembly);

                        ConfigMap logAndMetricsConfigMap = zkCluster.generateMetricsAndLogConfigMap(zkCluster.getLogging() instanceof ExternalLogging ?
                                configMapOperations.get(kafkaAssembly.getMetadata().getNamespace(), ((ExternalLogging) zkCluster.getLogging()).getName()) :
                                null);

                        this.zkService = zkCluster.generateService();
                        this.zkHeadlessService = zkCluster.generateHeadlessService();
                        this.zkMetricsAndLogsConfigMap = zkCluster.generateMetricsAndLogConfigMap(logAndMetricsConfigMap);

                        future.complete(this);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                }, true,
                res -> {
                    if (res.succeeded()) {
                        fut.complete((ReconciliationState) res.result());
                    } else {
                        fut.fail(res.cause());
                    }
                }
            );

            return fut;
        }

        Future<ReconciliationState> withZkDiff(Future<ReconcileResult<StatefulSet>> r) {
            return r.map(rr -> {
                this.zkDiffs = rr;
                return this;
            });
        }

        Future<ReconciliationState> withVoid(Future<?> r) {
            return r.map(this);
        }

        Future<ReconciliationState> zkScaleDown() {
            return withVoid(zkSetOperations.scaleDown(namespace, zkCluster.getName(), zkCluster.getReplicas()));
        }

        Future<ReconciliationState> zkService() {
            return withVoid(serviceOperations.reconcile(namespace, zkCluster.getServiceName(), zkService));
        }

        Future<ReconciliationState> zkHeadlessService() {
            return withVoid(serviceOperations.reconcile(namespace, zkCluster.getHeadlessServiceName(), zkHeadlessService));
        }

        Future<ReconciliationState> getReconciliationStateOfConfigMap(AbstractModel cluster, ConfigMap configMap, BiFunction<Boolean, Future<ReconcileResult<ConfigMap>>, Future<ReconciliationState>> function) {
            Future<ReconciliationState> result = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").<Boolean>executeBlocking(
                future -> {
                    ConfigMap current = configMapOperations.get(namespace, cluster.getAncillaryConfigName());
                    boolean onlyMetricsSettingChanged = onlyMetricsSettingChanged(current, configMap);
                    future.complete(onlyMetricsSettingChanged);
                }, res -> {
                    if (res.succeeded())  {
                        boolean onlyMetricsSettingChanged = res.result();
                        function.apply(onlyMetricsSettingChanged, configMapOperations.reconcile(namespace, cluster.getAncillaryConfigName(), configMap)).setHandler(res2 -> {
                            if (res2.succeeded())   {
                                result.complete(res2.result());
                            } else {
                                result.fail(res2.cause());
                            }
                        });
                    } else {
                        result.fail(res.cause());
                    }
                });
            return result;
        }

        Future<ReconciliationState> zkAncillaryCm() {
            return getReconciliationStateOfConfigMap(zkCluster, zkMetricsAndLogsConfigMap, this::withZkAncillaryCmChanged);
        }

        Future<ReconciliationState> zkNodesSecret() {
            return withVoid(secretOperations.reconcile(namespace, ZookeeperCluster.nodesSecretName(name),
                    zkCluster.generateNodesSecret(clusterCa, kafkaAssembly)));
        }

        Future<ReconciliationState> zkNetPolicy() {
            return withVoid(networkPolicyOperator.reconcile(namespace, ZookeeperCluster.policyName(name), zkCluster.generateNetworkPolicy()));
        }

        Future<ReconciliationState> zkStatefulSet() {
            StatefulSet zkSs = zkCluster.generateStatefulSet(isOpenShift);
            zkSs.getSpec().getTemplate().getMetadata().getAnnotations()
                    .put(Ca.ANNO_STRIMZI_IO_CLUSTER_CA_CERT_GENERATION, String.valueOf(getCaCertGeneration(this.clusterCa)));
            return withZkDiff(zkSetOperations.reconcile(namespace, zkCluster.getName(), zkSs));
        }

        Future<ReconciliationState> zkRollingUpdate() {
            return withVoid(zkSetOperations.maybeRollingUpdate(zkDiffs.resource(), pod ->
                isPodToRestart(zkDiffs.resource(), pod, zkAncillaryCmChange, this.clusterCa)
            ));
        }

        Future<ReconciliationState> zkScaleUp() {
            return withVoid(zkSetOperations.scaleUp(namespace, zkCluster.getName(), zkCluster.getReplicas()));
        }

        Future<ReconciliationState> zkServiceEndpointReadiness() {
            return withVoid(serviceOperations.endpointReadiness(namespace, zkService, 1_000, operationTimeoutMs));
        }

        Future<ReconciliationState> zkHeadlessServiceEndpointReadiness() {
            return withVoid(serviceOperations.endpointReadiness(namespace, zkHeadlessService, 1_000, operationTimeoutMs));
        }

        Future<ReconciliationState> withZkAncillaryCmChanged(boolean onlyMetricsSettingChanged, Future<ReconcileResult<ConfigMap>> r) {
            return r.map(rr -> {
                if (onlyMetricsSettingChanged) {
                    log.debug("Only metrics setting changed - not triggering rolling update");
                    this.zkAncillaryCmChange = false;
                } else {
                    this.zkAncillaryCmChange = rr instanceof ReconcileResult.Patched;
                }
                return this;
            });
        }

        Future<ReconciliationState> zkManualPodCleaning() {
            String reason = "manual pod cleaning";
            Future<StatefulSet> futss = zkSetOperations.getAsync(namespace, ZookeeperCluster.zookeeperClusterName(name));
            if (futss != null) {
                return futss.compose(ss -> {
                    if (ss != null) {
                        log.debug("{}: Cleaning Pods for StatefulSet {} to {}", reconciliation, ss.getMetadata().getName(), reason);
                        return zkSetOperations.maybeDeletePodAndPvc(ss);
                    }
                    return Future.succeededFuture();
                }).map(i -> this);
            }
            return Future.succeededFuture(this);
        }

        private Future<ReconciliationState> getKafkaClusterDescription() {
            Future<ReconciliationState> fut = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").<ReconciliationState>executeBlocking(
                future -> {
                    try {
                        this.kafkaCluster = KafkaCluster.fromCrd(kafkaAssembly);

                        ConfigMap logAndMetricsConfigMap = kafkaCluster.generateMetricsAndLogConfigMap(
                                kafkaCluster.getLogging() instanceof ExternalLogging ?
                                        configMapOperations.get(kafkaAssembly.getMetadata().getNamespace(), ((ExternalLogging) kafkaCluster.getLogging()).getName()) :
                                        null);
                        this.kafkaService = kafkaCluster.generateService();
                        this.kafkaHeadlessService = kafkaCluster.generateHeadlessService();
                        this.kafkaMetricsAndLogsConfigMap = logAndMetricsConfigMap;

                        future.complete(this);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                }, true,
                res -> {
                    if (res.succeeded()) {
                        fut.complete(res.result());
                    } else {
                        fut.fail(res.cause());
                    }
                }
            );
            return fut;
        }

        Future<ReconciliationState> withKafkaDiff(Future<ReconcileResult<StatefulSet>> r) {
            return r.map(rr -> {
                this.kafkaDiffs = rr;
                return this;
            });
        }

        Future<ReconciliationState> withKafkaAncillaryCmChanged(boolean onlyMetricsSettingChanged, Future<ReconcileResult<ConfigMap>> r) {
            return r.map(rr -> {
                if (onlyMetricsSettingChanged) {
                    log.debug("Only metrics setting changed - not triggering rolling update");
                    this.kafkaAncillaryCmChange = false;
                } else {
                    this.kafkaAncillaryCmChange = rr instanceof ReconcileResult.Patched;
                }
                return this;
            });
        }

        Future<ReconciliationState> kafkaInitServiceAccount() {
            return withVoid(serviceAccountOperator.reconcile(namespace,
                    KafkaCluster.initContainerServiceAccountName(kafkaCluster.getCluster()),
                    kafkaCluster.generateInitContainerServiceAccount()));
        }

        Future<ReconciliationState> kafkaInitClusterRoleBinding() {
            ClusterRoleBindingOperator.ClusterRoleBinding desired = kafkaCluster.generateClusterRoleBinding(namespace);
            Future<Void> fut = clusterRoleBindingOperator.reconcile(
                    KafkaCluster.initContainerClusterRoleBindingName(namespace, name),
                    desired);

            Future replacementFut = Future.future();

            fut.setHandler(res -> {
                if (res.failed())    {
                    if (desired == null && res.cause().getMessage().contains("403: Forbidden")) {
                        log.debug("Ignoring forbidden access to ClusterRoleBindings which seems not needed while Kafka rack awareness is disabled.");
                        replacementFut.complete();
                    } else {
                        replacementFut.fail(res.cause());
                    }
                } else {
                    replacementFut.complete();
                }
            });

            return withVoid(replacementFut);
        }

        Future<ReconciliationState> kafkaScaleDown() {
            return withVoid(kafkaSetOperations.scaleDown(namespace, kafkaCluster.getName(), kafkaCluster.getReplicas()));
        }

        Future<ReconciliationState> kafkaService() {
            return withVoid(serviceOperations.reconcile(namespace, kafkaCluster.getServiceName(), kafkaService));
        }

        Future<ReconciliationState> kafkaHeadlessService() {
            return withVoid(serviceOperations.reconcile(namespace, kafkaCluster.getHeadlessServiceName(), kafkaHeadlessService));
        }

        Future<ReconciliationState> kafkaExternalBootstrapService() {
            return withVoid(serviceOperations.reconcile(namespace, KafkaCluster.externalBootstrapServiceName(name), kafkaCluster.generateExternalBootstrapService()));
        }

        Future<ReconciliationState> kafkaReplicaServices() {
            int replicas = kafkaCluster.getReplicas();
            List<Future> serviceFutures = new ArrayList<>(replicas);

            for (int i = 0; i < replicas; i++) {
                serviceFutures.add(serviceOperations.reconcile(namespace, KafkaCluster.externalServiceName(name, i), kafkaCluster.generateExternalService(i)));
            }

            return withVoid(CompositeFuture.join(serviceFutures));
        }

        Future<ReconciliationState> kafkaBootstrapRoute() {
            Route route = kafkaCluster.generateExternalBootstrapRoute();

            if (routeOperations != null) {
                return withVoid(routeOperations.reconcile(namespace, KafkaCluster.serviceName(name), route));
            } else if (route != null)   {
                log.warn("{}: Exposing Kafka cluster {} using OpenShift Routes is available only on OpenShift", reconciliation, name);
                return withVoid(Future.failedFuture("Exposing Kafka cluster " + name + " using OpenShift Routes is available only on OpenShift"));
            }

            return withVoid(Future.succeededFuture());
        }

        Future<ReconciliationState> kafkaReplicaRoutes() {
            int replicas = kafkaCluster.getReplicas();
            List<Future> routeFutures = new ArrayList<>(replicas);

            for (int i = 0; i < replicas; i++) {
                Route route = kafkaCluster.generateExternalRoute(i);

                if (routeOperations != null) {
                    routeFutures.add(routeOperations.reconcile(namespace, KafkaCluster.externalServiceName(name, i), route));
                } else if (route != null)   {
                    log.warn("{}: Exposing Kafka cluster {} using OpenShift Routes is available only on OpenShift", reconciliation, name);
                    return withVoid(Future.failedFuture("Exposing Kafka cluster " + name + " using OpenShift Routes is available only on OpenShift"));
                }
            }

            return withVoid(CompositeFuture.join(routeFutures));
        }

        Future<ReconciliationState> kafkaExternalBootstrapServiceReady() {
            if (!kafkaCluster.isExposedWithLoadBalancer() && !kafkaCluster.isExposedWithNodePort()) {
                return withVoid(Future.succeededFuture());
            }

            Future blockingFuture = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
                future -> {
                    String serviceName = KafkaCluster.externalBootstrapServiceName(name);
                    Future<Void> address = null;

                    if (kafkaCluster.isExposedWithNodePort()) {
                        address = serviceOperations.hasNodePort(namespace, serviceName, 1_000, operationTimeoutMs);
                    } else {
                        address = serviceOperations.hasIngressAddress(namespace, serviceName, 1_000, operationTimeoutMs);
                    }

                    address.setHandler(res -> {
                        if (res.succeeded()) {
                            String bootstrapAddress = null;

                            if (kafkaCluster.isExposedWithLoadBalancer()) {
                                if (serviceOperations.get(namespace, serviceName).getStatus().getLoadBalancer().getIngress().get(0).getHostname() != null) {
                                    bootstrapAddress = serviceOperations.get(namespace, serviceName).getStatus().getLoadBalancer().getIngress().get(0).getHostname();
                                } else {
                                    bootstrapAddress = serviceOperations.get(namespace, serviceName).getStatus().getLoadBalancer().getIngress().get(0).getIp();
                                }

                                this.kafkaExternalBootstrapDnsName = bootstrapAddress;
                            } else if (kafkaCluster.isExposedWithNodePort()) {
                                bootstrapAddress = serviceOperations.get(namespace, serviceName).getSpec().getPorts().get(0).getNodePort().toString();
                            }

                            if (log.isTraceEnabled()) {
                                log.trace("{}: Found address {} for Service {}", reconciliation, bootstrapAddress, serviceName);
                            }

                            future.complete();
                        } else {
                            log.warn("{}: No address found for Service {}", reconciliation, serviceName);
                            future.fail("No address found for Service " + serviceName);
                        }
                    });
                }, res -> {
                    if (res.succeeded())    {
                        blockingFuture.complete();
                    } else  {
                        blockingFuture.fail(res.cause());
                    }
                });

            return withVoid(blockingFuture);
        }

        Future<ReconciliationState> kafkaReplicaServicesReady() {
            if (!kafkaCluster.isExposedWithLoadBalancer() && !kafkaCluster.isExposedWithNodePort()) {
                return withVoid(Future.succeededFuture());
            }

            Future blockingFuture = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
                future -> {
                    int replicas = kafkaCluster.getReplicas();
                    List<Future> routeFutures = new ArrayList<>(replicas);

                    for (int i = 0; i < replicas; i++) {
                        String serviceName = KafkaCluster.externalServiceName(name, i);
                        Future routeFuture = Future.future();

                        Future<Void> address = null;

                        if (kafkaCluster.isExposedWithNodePort()) {
                            address = serviceOperations.hasNodePort(namespace, serviceName, 1_000, operationTimeoutMs);
                        } else {
                            address = serviceOperations.hasIngressAddress(namespace, serviceName, 1_000, operationTimeoutMs);
                        }

                        int podNumber = i;

                        address.setHandler(res -> {
                            if (res.succeeded()) {
                                String serviceAddress = null;
                                if (kafkaCluster.isExposedWithLoadBalancer()) {
                                    if (serviceOperations.get(namespace, serviceName).getStatus().getLoadBalancer().getIngress().get(0).getHostname() != null) {
                                        serviceAddress = serviceOperations.get(namespace, serviceName).getStatus().getLoadBalancer().getIngress().get(0).getHostname();
                                    } else {
                                        serviceAddress = serviceOperations.get(namespace, serviceName).getStatus().getLoadBalancer().getIngress().get(0).getIp();
                                    }

                                    if (kafkaCluster.isExposedWithTls())    {
                                        this.kafkaExternalDnsNames.put(podNumber, serviceAddress);
                                    }
                                } else if (kafkaCluster.isExposedWithNodePort()) {
                                    serviceAddress = serviceOperations.get(namespace, serviceName).getSpec().getPorts().get(0).getNodePort().toString();
                                }

                                this.kafkaExternalAddresses.put(podNumber, serviceAddress);

                                if (log.isTraceEnabled()) {
                                    log.trace("{}: Found address {} for Service {}", reconciliation, serviceAddress, serviceName);
                                }

                                routeFuture.complete();
                            } else {
                                log.warn("{}: No address found for Service {}", reconciliation, serviceName);
                                routeFuture.fail("No address found for Service " + serviceName);
                            }
                        });

                        routeFutures.add(routeFuture);
                    }

                    CompositeFuture.join(routeFutures).setHandler(res -> {
                        if (res.succeeded()) {
                            future.complete();
                        } else  {
                            future.fail(res.cause());
                        }
                    });
                }, res -> {
                    if (res.succeeded())    {
                        blockingFuture.complete();
                    } else  {
                        blockingFuture.fail(res.cause());
                    }
                });

            return withVoid(blockingFuture);
        }

        Future<ReconciliationState> kafkaBootstrapRouteReady() {
            if (routeOperations == null || !kafkaCluster.isExposedWithRoute()) {
                return withVoid(Future.succeededFuture());
            }

            Future blockingFuture = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
                future -> {
                    String routeName = KafkaCluster.serviceName(name);
                    //Future future = Future.future();
                    Future<Void> address = routeOperations.hasAddress(namespace, routeName, 1_000, operationTimeoutMs);

                    address.setHandler(res -> {
                        if (res.succeeded()) {
                            String bootstrapAddress = routeOperations.get(namespace, routeName).getSpec().getHost();
                            this.kafkaExternalBootstrapDnsName = bootstrapAddress;

                            if (log.isTraceEnabled()) {
                                log.trace("{}: Found address {} for Route {}", reconciliation, bootstrapAddress, routeName);
                            }

                            future.complete();
                        } else {
                            log.warn("{}: No address found for Route {}", reconciliation, routeName);
                            future.fail("No address found for Route " + routeName);
                        }
                    });
                }, res -> {
                    if (res.succeeded())    {
                        blockingFuture.complete();
                    } else  {
                        blockingFuture.fail(res.cause());
                    }
                });

            return withVoid(blockingFuture);
        }

        Future<ReconciliationState> kafkaReplicaRoutesReady() {
            if (routeOperations == null || !kafkaCluster.isExposedWithRoute()) {
                return withVoid(Future.succeededFuture());
            }

            Future blockingFuture = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
                future -> {
                    int replicas = kafkaCluster.getReplicas();
                    List<Future> routeFutures = new ArrayList<>(replicas);

                    for (int i = 0; i < replicas; i++) {
                        String routeName = KafkaCluster.externalServiceName(name, i);
                        Future routeFuture = Future.future();
                        Future<Void> address = routeOperations.hasAddress(namespace, routeName, 1_000, operationTimeoutMs);
                        int podNumber = i;

                        address.setHandler(res -> {
                            if (res.succeeded()) {
                                String routeAddress = routeOperations.get(namespace, routeName).getSpec().getHost();
                                this.kafkaExternalAddresses.put(podNumber, routeAddress);
                                this.kafkaExternalDnsNames.put(podNumber, routeAddress);

                                if (log.isTraceEnabled()) {
                                    log.trace("{}: Found address {} for Route {}", reconciliation, routeAddress, routeName);
                                }

                                routeFuture.complete();
                            } else {
                                log.warn("{}: No address found for Route {}", reconciliation, routeName);
                                routeFuture.fail("No address found for Route " + routeName);
                            }
                        });

                        routeFutures.add(routeFuture);
                    }

                    CompositeFuture.join(routeFutures).setHandler(res -> {
                        if (res.succeeded()) {
                            future.complete();
                        } else  {
                            future.fail(res.cause());
                        }
                    });
                }, res -> {
                    if (res.succeeded())    {
                        blockingFuture.complete();
                    } else  {
                        blockingFuture.fail(res.cause());
                    }
                });

            return withVoid(blockingFuture);
        }

        Future<ReconciliationState> kafkaGenerateCertificates() {
            Future<ReconciliationState> result = Future.future();
            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").<ReconciliationState>executeBlocking(
                future -> {
                    try {
                        if (kafkaCluster.isExposedWithNodePort()) {
                            kafkaCluster.generateCertificates(kafkaAssembly,
                                    clusterCa, null, Collections.EMPTY_MAP);
                        } else {
                            kafkaCluster.generateCertificates(kafkaAssembly,
                                    clusterCa, kafkaExternalBootstrapDnsName, kafkaExternalDnsNames);
                        }
                        future.complete(this);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                },
                true,
                result.completer());
            return result;
        }

        Future<ReconciliationState> kafkaAncillaryCm() {
            return getReconciliationStateOfConfigMap(kafkaCluster, kafkaMetricsAndLogsConfigMap, this::withKafkaAncillaryCmChanged);
        }

        Future<ReconciliationState> kafkaBrokersSecret() {
            return withVoid(secretOperations.reconcile(namespace, KafkaCluster.brokersSecretName(name), kafkaCluster.generateBrokersSecret()));
        }

        Future<ReconciliationState> kafkaNetPolicy() {
            return withVoid(networkPolicyOperator.reconcile(namespace, KafkaCluster.policyName(name), kafkaCluster.generateNetworkPolicy()));
        }

        Future<ReconciliationState> kafkaStatefulSet() {
            kafkaCluster.setExternalAddresses(kafkaExternalAddresses);
            StatefulSet kafkaSs = kafkaCluster.generateStatefulSet(isOpenShift);
            kafkaSs.getSpec().getTemplate().getMetadata().getAnnotations()
                    .put(Ca.ANNO_STRIMZI_IO_CLUSTER_CA_CERT_GENERATION, String.valueOf(getCaCertGeneration(this.clusterCa)));
            kafkaSs.getSpec().getTemplate().getMetadata().getAnnotations()
                    .put(Ca.ANNO_STRIMZI_IO_CLIENTS_CA_CERT_GENERATION, String.valueOf(getCaCertGeneration(this.clientsCa)));
            return withKafkaDiff(kafkaSetOperations.reconcile(namespace, kafkaCluster.getName(), kafkaSs));
        }

        Future<ReconciliationState> kafkaRollingUpdate() {
            return withVoid(kafkaSetOperations.maybeRollingUpdate(kafkaDiffs.resource(), pod ->
                isPodToRestart(kafkaDiffs.resource(), pod, kafkaAncillaryCmChange, this.clusterCa, this.clientsCa)
            ));
        }

        Future<ReconciliationState> kafkaScaleUp() {
            return withVoid(kafkaSetOperations.scaleUp(namespace, kafkaCluster.getName(), kafkaCluster.getReplicas()));
        }

        Future<ReconciliationState> kafkaServiceEndpointReady() {
            return withVoid(serviceOperations.endpointReadiness(namespace, kafkaService, 1_000, operationTimeoutMs));
        }

        Future<ReconciliationState> kafkaHeadlessServiceEndpointReady() {
            return withVoid(serviceOperations.endpointReadiness(namespace, kafkaHeadlessService, 1_000, operationTimeoutMs));
        }

        Future<ReconciliationState> kafkaManualPodCleaning() {
            String reason = "manual pod cleaning";
            Future<StatefulSet> futss = kafkaSetOperations.getAsync(namespace, KafkaCluster.kafkaClusterName(name));
            if (futss != null) {
                return futss.compose(ss -> {
                    if (ss != null) {
                        log.debug("{}: Cleaning Pods for StatefulSet {} to {}", reconciliation, ss.getMetadata().getName(), reason);
                        return kafkaSetOperations.maybeDeletePodAndPvc(ss);
                    }
                    return Future.succeededFuture();
                }).map(i -> this);
            }
            return Future.succeededFuture(this);
        }

        private final Future<ReconciliationState> getTopicOperatorDescription() {
            Future<ReconciliationState> fut = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").<ReconciliationState>executeBlocking(
                future -> {
                    try {
                        this.topicOperator = TopicOperator.fromCrd(kafkaAssembly);

                        if (topicOperator != null) {
                            ConfigMap logAndMetricsConfigMap = topicOperator.generateMetricsAndLogConfigMap(
                                    topicOperator.getLogging() instanceof ExternalLogging ?
                                            configMapOperations.get(kafkaAssembly.getMetadata().getNamespace(), ((ExternalLogging) topicOperator.getLogging()).getName()) :
                                            null);
                            this.toDeployment = topicOperator.generateDeployment(isOpenShift);
                            this.toMetricsAndLogsConfigMap = logAndMetricsConfigMap;
                            this.toDeployment.getSpec().getTemplate().getMetadata().getAnnotations().put("strimzi.io/logging", this.toMetricsAndLogsConfigMap.getData().get("log4j2.properties"));
                        } else {
                            this.toDeployment = null;
                            this.toMetricsAndLogsConfigMap = null;
                        }

                        future.complete(this);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                }, true,
                res -> {
                    if (res.succeeded()) {
                        fut.complete(res.result());
                    } else {
                        fut.fail(res.cause());
                    }
                }
            );
            return fut;
        }

        Future<ReconciliationState> topicOperatorServiceAccount() {
            return withVoid(serviceAccountOperator.reconcile(namespace,
                    TopicOperator.topicOperatorServiceAccountName(name),
                    toDeployment != null ? topicOperator.generateServiceAccount() : null));
        }

        Future<ReconciliationState> topicOperatorRoleBinding() {
            String watchedNamespace = topicOperator != null ? topicOperator.getWatchedNamespace() : null;
            return withVoid(roleBindingOperator.reconcile(
                    watchedNamespace != null && !watchedNamespace.isEmpty() ?
                            watchedNamespace : namespace,
                    TopicOperator.roleBindingName(name),
                    toDeployment != null ? topicOperator.generateRoleBinding(namespace) : null));
        }

        Future<ReconciliationState> topicOperatorAncillaryCm() {
            return withVoid(configMapOperations.reconcile(namespace,
                    toDeployment != null ? topicOperator.getAncillaryConfigName() : TopicOperator.metricAndLogConfigsName(name),
                    toMetricsAndLogsConfigMap));
        }

        Future<ReconciliationState> topicOperatorDeployment() {
            if (this.toDeployment != null) {
                toDeployment.getSpec().getTemplate().getMetadata().getAnnotations()
                        .put(Ca.ANNO_STRIMZI_IO_CLUSTER_CA_CERT_GENERATION, String.valueOf(getCaCertGeneration(this.clusterCa)));
            }
            return withVoid(deploymentOperations.reconcile(namespace, TopicOperator.topicOperatorName(name), toDeployment));
        }

        Future<ReconciliationState> topicOperatorSecret() {
            return withVoid(secretOperations.reconcile(namespace, TopicOperator.secretName(name), topicOperator == null ? null : topicOperator.generateSecret(clusterCa)));
        }

        private final Future<ReconciliationState> getEntityOperatorDescription() {
            Future<ReconciliationState> fut = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").<ReconciliationState>executeBlocking(
                future -> {
                    try {
                        EntityOperator entityOperator = EntityOperator.fromCrd(kafkaAssembly);

                        if (entityOperator != null) {
                            EntityTopicOperator topicOperator = entityOperator.getTopicOperator();
                            EntityUserOperator userOperator = entityOperator.getUserOperator();

                            ConfigMap topicOperatorLogAndMetricsConfigMap = topicOperator != null ?
                                    topicOperator.generateMetricsAndLogConfigMap(topicOperator.getLogging() instanceof ExternalLogging ?
                                            configMapOperations.get(kafkaAssembly.getMetadata().getNamespace(), ((ExternalLogging) topicOperator.getLogging()).getName()) :
                                            null) : null;

                            ConfigMap userOperatorLogAndMetricsConfigMap = userOperator != null ?
                                    userOperator.generateMetricsAndLogConfigMap(userOperator.getLogging() instanceof ExternalLogging ?
                                            configMapOperations.get(kafkaAssembly.getMetadata().getNamespace(), ((ExternalLogging) userOperator.getLogging()).getName()) :
                                            null) : null;

                            int topicLogSettings = topicOperatorLogAndMetricsConfigMap.getData().get("log4j2.properties").hashCode();
                            topicOperator.setLoggingConfigurationHash(String.valueOf(topicLogSettings));

                            int userLogSettings = userOperatorLogAndMetricsConfigMap.getData().get("log4j2.properties").hashCode();
                            userOperator.setLoggingConfigurationHash(String.valueOf(userLogSettings));

                            this.entityOperator = entityOperator;
                            this.eoDeployment = entityOperator.generateDeployment(isOpenShift);
                            this.topicOperatorMetricsAndLogsConfigMap = topicOperatorLogAndMetricsConfigMap;
                            this.userOperatorMetricsAndLogsConfigMap = userOperatorLogAndMetricsConfigMap;
                        }

                        future.complete(this);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                }, true,
                res -> {
                    if (res.succeeded()) {
                        fut.complete(res.result());
                    } else {
                        fut.fail(res.cause());
                    }
                }
            );
            return fut;
        }

        Future<ReconciliationState> entityOperatorServiceAccount() {
            return withVoid(serviceAccountOperator.reconcile(namespace,
                    EntityOperator.entityOperatorServiceAccountName(name),
                    eoDeployment != null ? entityOperator.generateServiceAccount() : null));
        }

        Future<ReconciliationState> entityOperatorTopicOpRoleBinding() {
            String watchedNamespace = entityOperator != null && entityOperator.getTopicOperator() != null ?
                    entityOperator.getTopicOperator().getWatchedNamespace() : null;
            return withVoid(roleBindingOperator.reconcile(
                    watchedNamespace != null && !watchedNamespace.isEmpty() ?
                            watchedNamespace : namespace,
                    EntityTopicOperator.roleBindingName(name),
                    eoDeployment != null && entityOperator.getTopicOperator() != null ?
                            entityOperator.getTopicOperator().generateRoleBinding(namespace) : null));
        }

        Future<ReconciliationState> entityOperatorUserOpRoleBinding() {
            String watchedNamespace = entityOperator != null && entityOperator.getUserOperator() != null ?
                    entityOperator.getUserOperator().getWatchedNamespace() : null;
            return withVoid(roleBindingOperator.reconcile(
                    watchedNamespace != null && !watchedNamespace.isEmpty() ?
                            watchedNamespace : namespace,
                    EntityUserOperator.roleBindingName(name),
                    eoDeployment != null && entityOperator.getUserOperator() != null ?
                            entityOperator.getUserOperator().generateRoleBinding(namespace) : null));
        }

        Future<ReconciliationState> entityOperatorTopicOpAncillaryCm() {
            return withVoid(configMapOperations.reconcile(namespace,
                    eoDeployment != null && entityOperator.getTopicOperator() != null ?
                            entityOperator.getTopicOperator().getAncillaryConfigName() : EntityTopicOperator.metricAndLogConfigsName(name),
                    topicOperatorMetricsAndLogsConfigMap));
        }

        Future<ReconciliationState> entityOperatorUserOpAncillaryCm() {
            return withVoid(configMapOperations.reconcile(namespace,
                    eoDeployment != null && entityOperator.getUserOperator() != null ?
                            entityOperator.getUserOperator().getAncillaryConfigName() : EntityUserOperator.metricAndLogConfigsName(name),
                    userOperatorMetricsAndLogsConfigMap));
        }

        Future<ReconciliationState> entityOperatorDeployment() {
            if (this.entityOperator != null) {
                eoDeployment.getSpec().getTemplate().getMetadata().getAnnotations()
                        .put(Ca.ANNO_STRIMZI_IO_CLUSTER_CA_CERT_GENERATION, String.valueOf(getCaCertGeneration(this.clusterCa)));
            }
            return withVoid(deploymentOperations.reconcile(namespace, EntityOperator.entityOperatorName(name), eoDeployment));
        }

        Future<ReconciliationState> entityOperatorSecret() {
            return withVoid(secretOperations.reconcile(namespace, EntityOperator.secretName(name),
                    entityOperator == null ? null : entityOperator.generateSecret(clusterCa)));
        }

        private boolean isPodUpToDate(StatefulSet ss, Pod pod) {
            final int ssGeneration = StatefulSetOperator.getSsGeneration(ss);
            final int podGeneration = StatefulSetOperator.getPodGeneration(pod);
            log.debug("Rolling update of {}/{}: pod {} has {}={}; ss has {}={}",
                    ss.getMetadata().getNamespace(), ss.getMetadata().getName(), pod.getMetadata().getName(),
                    StatefulSetOperator.ANNOTATION_GENERATION, podGeneration,
                    StatefulSetOperator.ANNOTATION_GENERATION, ssGeneration);
            return ssGeneration == podGeneration;
        }

        private boolean isPodCaCertUpToDate(Pod pod, Ca ca) {
            final int caCertGeneration = getCaCertGeneration(ca);
            String podAnnotation = ca instanceof ClientsCa ?
                    Ca.ANNO_STRIMZI_IO_CLIENTS_CA_CERT_GENERATION :
                    Ca.ANNO_STRIMZI_IO_CLUSTER_CA_CERT_GENERATION;
            String generation = Util.annotations(pod).get(podAnnotation);
            final int podCaCertGeneration = generation != null ?
                    Integer.parseInt(generation) : Ca.INIT_GENERATION;
            return caCertGeneration == podCaCertGeneration;
        }

        private boolean isPodToRestart(StatefulSet ss, Pod pod, boolean isAncillaryCmChange, Ca... cas) {
            boolean isPodUpToDate = isPodUpToDate(ss, pod);
            boolean isPodCaCertUpToDate = true;
            boolean isCaCertsChanged = false;
            for (Ca ca: cas) {
                isCaCertsChanged |= ca.certRenewed() || ca.certsRemoved();
                isPodCaCertUpToDate &= isPodCaCertUpToDate(pod, ca);
            }

            if (log.isDebugEnabled()) {
                List<String> reasons = new ArrayList<>();
                for (Ca ca: cas) {
                    if (ca.certRenewed()) {
                        reasons.add(ca + " certificate renewal");
                    }
                    if (ca.certsRemoved()) {
                        reasons.add(ca + " certificate removal");
                    }
                    if (!isPodCaCertUpToDate(pod, ca)) {
                        reasons.add("Pod has old " + ca + " certificate generation");
                    }
                }
                if (isAncillaryCmChange) {
                    reasons.add("ancillary CM change");
                }
                if (!isPodUpToDate) {
                    reasons.add("Pod has old generation");
                }
                if (!reasons.isEmpty()) {
                    log.debug("{}: Rolling pod {} due to {}",
                            reconciliation, pod.getMetadata().getName(), reasons);
                }
            }
            return !isPodUpToDate || !isPodCaCertUpToDate || isAncillaryCmChange || isCaCertsChanged;
        }

        private int getCaCertGeneration(Ca ca) {
            String generation = Util.annotations(ca.caCertSecret()).get(Ca.ANNO_STRIMZI_IO_CA_CERT_GENERATION);
            return generation != null ? Integer.parseInt(generation) : Ca.INIT_GENERATION;
        }
    }

    private final Future<CompositeFuture> deleteKafka(Reconciliation reconciliation) {
        String namespace = reconciliation.namespace();
        String name = reconciliation.name();
        log.debug("{}: delete kafka {}", reconciliation, name);
        String kafkaSsName = KafkaCluster.kafkaClusterName(name);
        StatefulSet ss = kafkaSetOperations.get(namespace, kafkaSsName);
        int replicas = ss != null ? ss.getSpec().getReplicas() : 0;
        boolean deleteClaims = ss == null ? false : KafkaCluster.deleteClaim(ss);
        List<Future> result = new ArrayList<>(deleteClaims ? replicas : 0);

        if (deleteClaims) {
            log.debug("{}: delete kafka {} PVCs", reconciliation, name);

            for (int i = 0; i < replicas; i++) {
                result.add(pvcOperations.reconcile(namespace,
                        KafkaCluster.getPersistentVolumeClaimName(kafkaSsName, i), null));
            }
        }

        return CompositeFuture.join(result);
    }

    private final Future<CompositeFuture> deleteZk(Reconciliation reconciliation) {
        String namespace = reconciliation.namespace();
        String name = reconciliation.name();
        log.debug("{}: delete zookeeper {}", reconciliation, name);
        String zkSsName = ZookeeperCluster.zookeeperClusterName(name);
        StatefulSet ss = zkSetOperations.get(namespace, zkSsName);
        boolean deleteClaims = ss == null ? false : ZookeeperCluster.deleteClaim(ss);
        List<Future> result = new ArrayList<>(deleteClaims ? ss.getSpec().getReplicas() : 0);

        if (deleteClaims) {
            log.debug("{}: delete zookeeper {} PVCs", reconciliation, name);
            for (int i = 0; i < ss.getSpec().getReplicas(); i++) {
                result.add(pvcOperations.reconcile(namespace, ZookeeperCluster.getPersistentVolumeClaimName(zkSsName, i), null));
            }
        }

        return CompositeFuture.join(result);
    }

    @Override
    protected Future<Void> delete(Reconciliation reconciliation) {
        return deleteKafka(reconciliation)
                .compose(i -> deleteZk(reconciliation))
                .map((Void) null);
    }

    @Override
    protected List<HasMetadata> getResources(String namespace, Labels selector) {
        // TODO: Search for PVCs!
        return Collections.EMPTY_LIST;
    }
}