/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.ai.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.huawei.hicampus.claw.ai.types.Api;
import com.huawei.hicampus.claw.ai.types.Cost;
import com.huawei.hicampus.claw.ai.types.InputModality;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.ModelCost;
import com.huawei.hicampus.claw.ai.types.Provider;
import com.huawei.hicampus.claw.ai.types.Usage;
import com.huawei.hicampus.claw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelRegistryTest {
    @TempDir
    Path tempDir;

    private ModelRegistry registry;

    private String previousHome;

    @BeforeEach
    void setUp() {
        previousHome = System.getProperty(ClawConstants.Home.PROPERTY);
        System.setProperty(ClawConstants.Home.PROPERTY, tempDir.toString());
        registry = new ModelRegistry();
    }

    @AfterEach
    void tearDown() {
        if (previousHome == null) {
            System.clearProperty(ClawConstants.Home.PROPERTY);
        } else {
            System.setProperty(ClawConstants.Home.PROPERTY, previousHome);
        }
    }

    @Nested
    class RegisterAndLookup {
        @Test
        void registerSingleModel() {
            var model = model(Provider.CAMPUSMATE, "model-a", "Model A");
            registry.register(model);

            assertEquals(
                    model, registry.getModel(Provider.CAMPUSMATE, "model-a").orElseThrow());
        }

        @Test
        void registerReplacesExisting() {
            registry.register(model(Provider.CAMPUSMATE, "model-a", "Original"));
            registry.register(model(Provider.CAMPUSMATE, "model-a", "Replacement"));

            assertEquals(
                    "Replacement",
                    registry.getModel(Provider.CAMPUSMATE, "model-a")
                            .orElseThrow()
                            .name());
        }

        @Test
        void registerAllMultipleModels() {
            registry.registerAll(List.of(
                    model(Provider.CAMPUSMATE, "model-a", "A"),
                    model(Provider.CAMPUSMATE, "model-b", "B"),
                    model(Provider.CUSTOM, "model-c", "C")));

            assertEquals(2, registry.getModels(Provider.CAMPUSMATE).size());
            assertEquals(1, registry.getModels(Provider.CUSTOM).size());
        }

        @Test
        void registerNullModelThrows() {
            assertThrows(NullPointerException.class, () -> registry.register(null));
        }

        @Test
        void registerAllNullThrows() {
            assertThrows(NullPointerException.class, () -> registry.registerAll(null));
        }
    }

    @Nested
    class GetModel {
        @Test
        void returnsEmptyForUnknownProvider() {
            registry.register(model(Provider.CAMPUSMATE, "model-a", "A"));

            assertTrue(registry.getModel(Provider.CUSTOM, "model-a").isEmpty());
        }

        @Test
        void returnsEmptyForUnknownModelId() {
            registry.register(model(Provider.CAMPUSMATE, "model-a", "A"));

            assertTrue(registry.getModel(Provider.CAMPUSMATE, "missing").isEmpty());
        }

        @Test
        void returnsEmptyWhenRegistryIsEmpty() {
            assertTrue(registry.getModel(Provider.CAMPUSMATE, "model-a").isEmpty());
        }
    }

    @Nested
    class GetModels {
        @Test
        void returnsAllModelsForProvider() {
            registry.register(model(Provider.CAMPUSMATE, "model-a", "A"));
            registry.register(model(Provider.CAMPUSMATE, "model-b", "B"));
            registry.register(model(Provider.CUSTOM, "model-c", "C"));

            assertEquals(2, registry.getModels(Provider.CAMPUSMATE).size());
            assertEquals(1, registry.getModels(Provider.CUSTOM).size());
        }

        @Test
        void returnsEmptyListForUnknownProvider() {
            assertTrue(registry.getModels(Provider.CUSTOM).isEmpty());
        }

        @Test
        void returnedListIsUnmodifiable() {
            registry.register(model(Provider.CAMPUSMATE, "model-a", "A"));
            var registered = registry.getModels(Provider.CAMPUSMATE);

            assertThrows(UnsupportedOperationException.class, () -> registered.add(null));
        }
    }

    @Nested
    class GetProviders {
        @Test
        void returnsProvidersWithModels() {
            registry.register(model(Provider.CAMPUSMATE, "model-a", "A"));
            registry.register(model(Provider.CUSTOM, "model-b", "B"));

            assertEquals(2, registry.getProviders().size());
            assertTrue(registry.getProviders().containsAll(List.of(Provider.CAMPUSMATE, Provider.CUSTOM)));
        }

        @Test
        void returnsEmptyWhenNoModels() {
            assertTrue(registry.getProviders().isEmpty());
        }

        @Test
        void returnedListIsUnmodifiable() {
            registry.register(model(Provider.CAMPUSMATE, "model-a", "A"));
            var providers = registry.getProviders();

            assertThrows(UnsupportedOperationException.class, () -> providers.add(null));
        }
    }

    @Nested
    class Clear {
        @Test
        void removesAllModels() {
            registry.register(model(Provider.CAMPUSMATE, "model-a", "A"));
            registry.register(model(Provider.CUSTOM, "model-b", "B"));

            registry.clear();

            assertTrue(registry.getAllModels().isEmpty());
            assertTrue(registry.getProviders().isEmpty());
        }

        @Test
        void clearOnEmptyRegistryIsNoOp() {
            assertDoesNotThrow(registry::clear);
        }
    }

    @Nested
    class UnregisterByProvider {
        @Test
        void removesOnlyThatProvider() {
            registry.register(model(Provider.CAMPUSMATE, "model-a", "A"));
            registry.register(model(Provider.CAMPUSMATE, "model-b", "B"));
            registry.register(model(Provider.CUSTOM, "model-c", "C"));

            registry.unregisterByProvider(Provider.CAMPUSMATE);

            assertTrue(registry.getModels(Provider.CAMPUSMATE).isEmpty());
            assertEquals(1, registry.getModels(Provider.CUSTOM).size());
        }

        @Test
        void unregisterUnknownProviderIsNoOp() {
            registry.register(model(Provider.CAMPUSMATE, "model-a", "A"));

            assertDoesNotThrow(() -> registry.unregisterByProvider(Provider.CUSTOM));
            assertEquals(1, registry.getModels(Provider.CAMPUSMATE).size());
        }

        @Test
        void nullProviderThrows() {
            assertThrows(NullPointerException.class, () -> registry.unregisterByProvider(null));
        }
    }

    @Nested
    class ExternalLoading {
        @Test
        void initWithoutExternalJsonLeavesRegistryEmpty() {
            registry.init();

            assertTrue(registry.getAllModels().isEmpty());
        }

        @Test
        void initLoadsUserJsonAndOverridesExistingModel() throws Exception {
            registry.register(model(Provider.CUSTOM, "external-model", "Original"));
            Path file = tempDir.resolve("agent/models.json");
            writeModels(file, List.of(model(Provider.CUSTOM, "external-model", "User override")));

            registry.init();

            assertEquals(
                    "User override",
                    registry.getModel(Provider.CUSTOM, "external-model")
                            .orElseThrow()
                            .name());
        }

        @Test
        void loadFromJsonFileRegistersModels() throws Exception {
            Path file = tempDir.resolve("models.json");
            writeModels(file, List.of(model(Provider.CUSTOM, "external-model", "External")));

            int loaded = registry.loadFromJsonFile(file);

            assertEquals(1, loaded);
            assertEquals(
                    "External",
                    registry.getModel(Provider.CUSTOM, "external-model")
                            .orElseThrow()
                            .name());
        }

        @Test
        void loadFromClasspathResourceRegistersModels() throws Exception {
            String resourceName = "model-registry-test-" + UUID.randomUUID() + ".json";
            Path fixture = classpathFixture(resourceName);
            writeModels(fixture, List.of(model(Provider.CUSTOM, "classpath-model", "Classpath")));

            try {
                int loaded = registry.loadFromClasspathResource("/" + resourceName);

                assertEquals(1, loaded);
                assertEquals(
                        "Classpath",
                        registry.getModel(Provider.CUSTOM, "classpath-model")
                                .orElseThrow()
                                .name());
            } finally {
                Files.deleteIfExists(fixture);
            }
        }

        @Test
        void invalidJsonReturnsZeroWithoutChangingRegistry() throws Exception {
            Path file = tempDir.resolve("invalid.json");
            Files.writeString(file, "{invalid", StandardCharsets.UTF_8);

            assertEquals(0, registry.loadFromJsonFile(file));
            assertTrue(registry.getAllModels().isEmpty());
        }
    }

    @Nested
    class Utilities {
        @Test
        void calculatesCostFromTokenUsage() {
            Model priced = model(Provider.CUSTOM, "priced", "Priced");
            Usage usage = new Usage(2_000_000, 3_000_000, 4_000_000, 5_000_000, 14_000_000, Cost.empty());

            Cost cost = ModelRegistry.calculateCost(priced, usage);

            assertEquals(new Cost(2.0, 6.0, 2.0, 0.0, 10.0), cost);
        }

        @Test
        void returnsEmptyCostWhenModelHasNoPricing() {
            Model unpriced = new Model(
                    "unpriced",
                    "Unpriced",
                    Api.OPENAI_COMPLETIONS,
                    Provider.CUSTOM,
                    null,
                    false,
                    List.of(InputModality.TEXT),
                    null,
                    128_000,
                    8_192,
                    null,
                    null,
                    null);

            assertEquals(Cost.empty(), ModelRegistry.calculateCost(unpriced, Usage.empty()));
        }

        @Test
        void comparesProviderAndModelIdentityExactly() {
            Model campusMate = model(Provider.CAMPUSMATE, "same-id", "CampusMate");
            Model legacy = model(Provider.CUSTOM, "same-id", "Legacy");

            assertTrue(ModelRegistry.modelsAreEqual(campusMate, campusMate));
            assertFalse(ModelRegistry.modelsAreEqual(campusMate, legacy));
        }

        @Test
        void recognizesSupportedXhighFamilies() {
            assertTrue(ModelRegistry.supportsXhigh(model(Provider.CUSTOM, "gpt-5.4", "GPT")));
            assertFalse(ModelRegistry.supportsXhigh(model(Provider.CUSTOM, "ordinary", "Ordinary")));
        }
    }

    private void writeModels(Path file, List<Model> definitions) throws Exception {
        Files.createDirectories(file.getParent());
        new ObjectMapper().writeValue(file.toFile(), definitions);
    }

    private Path classpathFixture(String name) throws Exception {
        return Path.of(ModelRegistryTest.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .resolve(name);
    }

    private static Model model(Provider provider, String id, String name) {
        return new Model(
                id,
                name,
                Api.OPENAI_COMPLETIONS,
                provider,
                null,
                false,
                List.of(InputModality.TEXT),
                new ModelCost(1.0, 2.0, 0.5, 0.0),
                128_000,
                8_192,
                null,
                null,
                null);
    }
}
