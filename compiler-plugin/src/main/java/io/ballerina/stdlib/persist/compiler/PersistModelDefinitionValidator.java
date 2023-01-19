/*
 * Copyright (c) 2023, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.stdlib.persist.compiler;

import io.ballerina.compiler.syntax.tree.ArrayTypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.BuiltinSimpleNameReferenceNode;
import io.ballerina.compiler.syntax.tree.ModuleMemberDeclarationNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeList;
import io.ballerina.compiler.syntax.tree.NodeLocation;
import io.ballerina.compiler.syntax.tree.OptionalTypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.QualifiedNameReferenceNode;
import io.ballerina.compiler.syntax.tree.RecordFieldNode;
import io.ballerina.compiler.syntax.tree.RecordFieldWithDefaultValueNode;
import io.ballerina.compiler.syntax.tree.RecordTypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.SimpleNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.TypeDefinitionNode;
import io.ballerina.compiler.syntax.tree.TypeDescriptorNode;
import io.ballerina.projects.ProjectKind;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.projects.util.ProjectConstants;
import io.ballerina.stdlib.persist.compiler.model.Entity;
import io.ballerina.stdlib.persist.compiler.model.RelationField;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;

import java.io.File;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.ballerina.stdlib.persist.compiler.Constants.BallerinaTimeTypes.CIVIL;
import static io.ballerina.stdlib.persist.compiler.Constants.BallerinaTimeTypes.DATE;
import static io.ballerina.stdlib.persist.compiler.Constants.BallerinaTimeTypes.TIME_OF_DAY;
import static io.ballerina.stdlib.persist.compiler.Constants.BallerinaTimeTypes.UTC;
import static io.ballerina.stdlib.persist.compiler.Constants.BallerinaTypes.BOOLEAN;
import static io.ballerina.stdlib.persist.compiler.Constants.BallerinaTypes.BYTE;
import static io.ballerina.stdlib.persist.compiler.Constants.BallerinaTypes.DECIMAL;
import static io.ballerina.stdlib.persist.compiler.Constants.BallerinaTypes.FLOAT;
import static io.ballerina.stdlib.persist.compiler.Constants.BallerinaTypes.INT;
import static io.ballerina.stdlib.persist.compiler.Constants.BallerinaTypes.STRING;
import static io.ballerina.stdlib.persist.compiler.Constants.PERSIST_DIRECTORY;
import static io.ballerina.stdlib.persist.compiler.Constants.TIME_MODULE;
import static io.ballerina.stdlib.persist.compiler.DiagnosticsCodes.PERSIST_101;
import static io.ballerina.stdlib.persist.compiler.DiagnosticsCodes.PERSIST_102;
import static io.ballerina.stdlib.persist.compiler.DiagnosticsCodes.PERSIST_103;
import static io.ballerina.stdlib.persist.compiler.DiagnosticsCodes.PERSIST_201;
import static io.ballerina.stdlib.persist.compiler.DiagnosticsCodes.PERSIST_202;
import static io.ballerina.stdlib.persist.compiler.DiagnosticsCodes.PERSIST_203;
import static io.ballerina.stdlib.persist.compiler.DiagnosticsCodes.PERSIST_204;
import static io.ballerina.stdlib.persist.compiler.DiagnosticsCodes.PERSIST_205;
import static io.ballerina.stdlib.persist.compiler.DiagnosticsCodes.PERSIST_206;
import static io.ballerina.stdlib.persist.compiler.DiagnosticsCodes.PERSIST_301;
import static io.ballerina.stdlib.persist.compiler.DiagnosticsCodes.PERSIST_302;
import static io.ballerina.stdlib.persist.compiler.DiagnosticsCodes.PERSIST_303;
import static io.ballerina.stdlib.persist.compiler.DiagnosticsCodes.PERSIST_304;
import static io.ballerina.stdlib.persist.compiler.DiagnosticsCodes.PERSIST_305;

/**
 * Persist model definition validator.
 */
public class PersistModelDefinitionValidator implements AnalysisTask<SyntaxNodeAnalysisContext> {
    private final Map<String, Entity> entities = new HashMap<>();
    private final List<String> entityNames = new ArrayList<>();
    private final Map<String, List<RelationField>> deferredRelationKeyEntities = new HashMap<>();

    @Override
    public void perform(SyntaxNodeAnalysisContext ctx) {
        if (!isPersistModelDefinitionDocument(ctx)) {
            return;
        }

        if (Utils.hasCompilationErrors(ctx)) {
            return;
        }

        ModulePartNode rootNode = (ModulePartNode) ctx.node();
        List<TypeDefinitionNode> foundEntities = new ArrayList<>();
        for (ModuleMemberDeclarationNode member : rootNode.members()) {
            if (member instanceof TypeDefinitionNode) {
                TypeDefinitionNode typeDefinitionNode = (TypeDefinitionNode) member;
                TypeDescriptorNode typeDescriptorNode = (TypeDescriptorNode) typeDefinitionNode.typeDescriptor();
                if (typeDescriptorNode instanceof RecordTypeDescriptorNode) {
                    foundEntities.add(typeDefinitionNode);
                    this.entityNames.add(typeDefinitionNode.typeName().text().trim());
                    continue;
                }
            }
            ctx.reportDiagnostic(DiagnosticFactory.createDiagnostic(
                    new DiagnosticInfo(PERSIST_101.getCode(), PERSIST_101.getMessage(), PERSIST_101.getSeverity()),
                    member.location()));
        }

        for (TypeDefinitionNode typeDefinitionNode : foundEntities) {
            String entityName = typeDefinitionNode.typeName().text().trim();
            TypeDescriptorNode typeDescriptorNode = (TypeDescriptorNode) typeDefinitionNode.typeDescriptor();

            Entity entity = new Entity(entityName, typeDefinitionNode.typeName().location(),
                    ((RecordTypeDescriptorNode) typeDescriptorNode));
            validateEntityRecordProperties(entity);
            validateEntityFields(entity);
            validateIdentifierFieldCount(entity);
            validateEntityRelations(entity);

            if (this.deferredRelationKeyEntities.containsKey(entityName)) {
                List<RelationField> annotatedFields = this.deferredRelationKeyEntities.get(entityName);
                for (RelationField field : annotatedFields) {
                    validateRelation(field, this.entities.get(field.getContainingEntity()), entity, entity);
                }
            }

            entity.getDiagnostics().forEach((ctx::reportDiagnostic));
            this.entities.put(entityName, entity);
        }
    }

    private void validateEntityRecordProperties(Entity entity) {
        // Check whether the entity is a closed record
        RecordTypeDescriptorNode recordTypeDescriptorNode = entity.getTypeDescriptorNode();
        if (recordTypeDescriptorNode.bodyStartDelimiter().kind() != SyntaxKind.OPEN_BRACE_PIPE_TOKEN) {
            entity.reportDiagnostic(PERSIST_102.getCode(), PERSIST_102.getMessage(), PERSIST_102.getSeverity(),
                    recordTypeDescriptorNode.location());
        }
    }

    private void validateEntityFields(Entity entity) {
        // Check whether the entity has rest field initialization
        RecordTypeDescriptorNode typeDescriptorNode = entity.getTypeDescriptorNode();
        if (typeDescriptorNode.recordRestDescriptor().isPresent()) {
            entity.reportDiagnostic(PERSIST_201.getCode(), PERSIST_201.getMessage(), PERSIST_201.getSeverity(),
                    typeDescriptorNode.recordRestDescriptor().get().location());
        }

        NodeList<Node> fields = typeDescriptorNode.fields();
        for (Node fieldNode : fields) {
            RecordFieldNode recordFieldNode;
            if (fieldNode instanceof RecordFieldNode) {
                recordFieldNode = (RecordFieldNode) fieldNode;
                if (recordFieldNode.readonlyKeyword().isPresent()) {
                    entity.incrementReadonlyFieldCount();
                    entity.addIdentifierField(recordFieldNode.fieldName().text().trim());
                }
            } else if (fieldNode instanceof RecordFieldWithDefaultValueNode) {
                RecordFieldWithDefaultValueNode defaultableNode = (RecordFieldWithDefaultValueNode) fieldNode;
                if (defaultableNode.readonlyKeyword().isPresent()) {
                    entity.incrementReadonlyFieldCount();
                    entity.addIdentifierField(defaultableNode.fieldName().text().trim());
                }
                entity.reportDiagnostic(PERSIST_202.getCode(), PERSIST_202.getMessage(), PERSIST_202.getSeverity(),
                        fieldNode.location());
                continue;
            } else {
                // Inherited Field
                entity.reportDiagnostic(PERSIST_203.getCode(), PERSIST_203.getMessage(), PERSIST_203.getSeverity(),
                        fieldNode.location());
                continue;
            }

            // Check if optional field
            if (recordFieldNode.questionMarkToken().isPresent()) {
                entity.reportDiagnostic(PERSIST_204.getCode(), PERSIST_204.getMessage(), PERSIST_204.getSeverity(),
                        recordFieldNode.location());
            }

            Node typeNode = recordFieldNode.typeName();
            Node processedTypeNode = typeNode;
            String typeNamePostfix = "";
            boolean isArrayType = false;
            if (processedTypeNode instanceof OptionalTypeDescriptorNode) {
                processedTypeNode = ((OptionalTypeDescriptorNode) processedTypeNode).typeDescriptor();
            }
            if (processedTypeNode instanceof ArrayTypeDescriptorNode) {
                isArrayType = true;
                ArrayTypeDescriptorNode arrayTypeDescriptorNode = ((ArrayTypeDescriptorNode) processedTypeNode);
                processedTypeNode = arrayTypeDescriptorNode.memberTypeDesc();
                typeNamePostfix = SyntaxKind.OPEN_BRACKET_TOKEN.stringValue() +
                        SyntaxKind.CLOSE_BRACKET_TOKEN.stringValue();
            }

            if (processedTypeNode instanceof BuiltinSimpleNameReferenceNode) {
                String type = ((BuiltinSimpleNameReferenceNode) processedTypeNode).name().text();
                if (isValidSimpleType(type)) {
                    if (isArrayType) {
                        entity.reportDiagnostic(PERSIST_206.getCode(),
                                MessageFormat.format(PERSIST_206.getMessage(), type),
                                PERSIST_206.getSeverity(), processedTypeNode.location());
                    }
                } else if (!(type.equals(BYTE) && isArrayType)) {
                    entity.reportDiagnostic(PERSIST_205.getCode(), MessageFormat.format(PERSIST_205.getMessage(),
                                    type + typeNamePostfix), PERSIST_205.getSeverity(),
                            typeNode.location());
                }
                entity.addNonRelationField(recordFieldNode.fieldName().text().trim(), recordFieldNode.location());
            } else if (processedTypeNode instanceof QualifiedNameReferenceNode) {
                // Support only time constructs
                QualifiedNameReferenceNode qualifiedName = (QualifiedNameReferenceNode) processedTypeNode;
                String modulePrefix = qualifiedName.modulePrefix().text();
                String identifier = qualifiedName.identifier().text();
                if (isValidImportedType(modulePrefix, identifier)) {
                    if (isArrayType) {
                        entity.reportDiagnostic(PERSIST_206.getCode(),
                                MessageFormat.format(PERSIST_206.getMessage(), modulePrefix + ":" + identifier),
                                PERSIST_206.getSeverity(), typeNode.location());
                    }
                } else {
                    entity.reportDiagnostic(PERSIST_205.getCode(), MessageFormat.format(PERSIST_205.getMessage(),
                                    modulePrefix + ":" + identifier + typeNamePostfix), PERSIST_205.getSeverity(),
                            typeNode.location());
                }
                entity.addNonRelationField(recordFieldNode.fieldName().text().trim(), recordFieldNode.location());
            } else if (processedTypeNode instanceof SimpleNameReferenceNode) {
                String typeName = ((SimpleNameReferenceNode) processedTypeNode).name().text().trim();
                if (this.entityNames.contains(typeName)) {
                    entity.setContainsRelations(true);
                    entity.addRelationField(new RelationField(typeName, isArrayType, recordFieldNode.location(),
                            entity.getEntityName()));
                } else {
                    entity.reportDiagnostic(PERSIST_205.getCode(), MessageFormat.format(PERSIST_205.getMessage(),
                                    typeName + typeNamePostfix), PERSIST_205.getSeverity(),
                            typeNode.location());
                }
            } else {
                entity.reportDiagnostic(PERSIST_205.getCode(), MessageFormat.format(PERSIST_205.getMessage(),
                                Utils.getTypeName(processedTypeNode)), PERSIST_205.getSeverity(),
                        typeNode.location());
            }
        }
    }

    private boolean isValidSimpleType(String type) {
        switch (type) {
            case INT:
            case BOOLEAN:
            case DECIMAL:
            case FLOAT:
            case STRING:
                return true;
            default:
                return false;
        }
    }

    private boolean isValidImportedType(String modulePrefix, String identifier) {
        if (!modulePrefix.equals(TIME_MODULE)) {
            return false;
        }
        switch (identifier) {
            case DATE:
            case TIME_OF_DAY:
            case UTC:
            case CIVIL:
                return true;
            default:
                return false;
        }
    }

    private void validateIdentifierFieldCount(Entity entity) {
        if (entity.getReadonlyFieldCount() == 0) {
            entity.reportDiagnostic(PERSIST_103.getCode(), MessageFormat.format(PERSIST_103.getMessage(),
                    entity.getEntityName()), PERSIST_103.getSeverity(), entity.getEntityNameLocation());
        }
    }

    private void validateEntityRelations(Entity entity) {
        if (!entity.isContainsRelations()) {
            return;
        }

        List<String> validRelationTypes = new ArrayList<>();
        for (RelationField relationField : entity.getRelationFields()) {
            String referredEntity = relationField.getType();

            if (relationField.getType().equals(relationField.getContainingEntity())) {
                entity.reportDiagnostic(PERSIST_301.getCode(), PERSIST_301.getMessage(),
                        PERSIST_301.getSeverity(), relationField.getLocation());
                break;
            }

            // Duplicated Relations
            if (validRelationTypes.contains(relationField.getType())) {
                entity.reportDiagnostic(PERSIST_303.getCode(), PERSIST_303.getMessage(),
                        PERSIST_303.getSeverity(), relationField.getLocation());
                break;
            }

            validRelationTypes.add(relationField.getType());

            if (this.entities.containsKey(referredEntity)) {
                validateRelation(relationField, entity, this.entities.get(referredEntity), entity);
                if (this.deferredRelationKeyEntities.containsKey(entity.getEntityName())) {
                    List<RelationField> referredFields = this.deferredRelationKeyEntities.get(entity.getEntityName());
                    referredFields.removeIf(field -> field.getContainingEntity().equals(referredEntity));
                    if (referredFields.isEmpty()) {
                        this.deferredRelationKeyEntities.remove(entity.getEntityName());
                    }
                }
            } else {
                if (this.deferredRelationKeyEntities.containsKey(referredEntity)) {
                    this.deferredRelationKeyEntities.get(referredEntity).add(relationField);
                } else {
                    List<RelationField> references = new ArrayList<>();
                    references.add(relationField);
                    this.deferredRelationKeyEntities.put(referredEntity, references);
                }
            }
        }
    }

    private void validateRelation(RelationField processingField, Entity processingEntity, Entity referredEntity,
                                  Entity reportDiagnosticsEntity) {

        RelationField referredField = null;
        for (RelationField relationField : referredEntity.getRelationFields()) {
            if (processingField.getContainingEntity().equals(relationField.getType())) {
                referredField = relationField;
                break;
            }
        }

        if (referredField == null) {
            reportDiagnosticsEntity.reportDiagnostic(PERSIST_302.getCode(),
                    MessageFormat.format(PERSIST_302.getMessage(), referredEntity.getEntityName(),
                            processingField.getContainingEntity()), PERSIST_302.getSeverity(),
                    processingField.getLocation());
            return;
        }

        // 1:1 relations
        if (!processingField.isArrayType() && !referredField.isArrayType()) {
            // Processing second entity
            if (processingEntity.getEntityName().equals(reportDiagnosticsEntity.getEntityName())) {
                validatePresenceOfForeignKey(referredEntity, processingEntity, reportDiagnosticsEntity);
            } else {
                validatePresenceOfForeignKey(processingEntity, referredEntity, reportDiagnosticsEntity);
            }
            return;
        }

        // n:m relations
        if (processingField.isArrayType() && referredField.isArrayType()) {
            reportDiagnosticsEntity.reportDiagnostic(PERSIST_305.getCode(),
                    MessageFormat.format(PERSIST_305.getMessage(), referredEntity.getEntityName()),
                    PERSIST_305.getSeverity(), processingField.getLocation());
            return;
        }

        // 1:n relations
        if (!processingField.isArrayType()) {
            validatePresenceOfForeignKey(processingEntity, referredEntity, reportDiagnosticsEntity);
        } else {
            validatePresenceOfForeignKey(referredEntity, processingEntity, reportDiagnosticsEntity);
        }
    }

    private void validatePresenceOfForeignKey(Entity parentEntity, Entity childEntity,
                                              Entity reportDiagnosticsEntity) {
        for (String identifierField : childEntity.getIdentifierFields()) {
            String foreignKey = childEntity.getEntityName().toLowerCase(Locale.ENGLISH) +
                    identifierField.substring(0, 1).toUpperCase(Locale.ENGLISH) + identifierField.substring(1);
            NodeLocation foreignKeyFieldLocation = parentEntity.getNonRelationFields().get(foreignKey);
            if (foreignKeyFieldLocation != null) {
                reportDiagnosticsEntity.reportDiagnostic(PERSIST_304.getCode(),
                        MessageFormat.format(PERSIST_304.getMessage(), foreignKey, childEntity.getEntityName()),
                        PERSIST_304.getSeverity(), foreignKeyFieldLocation);
            }
        }
    }


    private boolean isPersistModelDefinitionDocument(SyntaxNodeAnalysisContext ctx) {
        try {
            if (ctx.currentPackage().project().kind().equals(ProjectKind.SINGLE_FILE_PROJECT)) {
                Path balFilePath = ctx.currentPackage().project().sourceRoot().toAbsolutePath();
                Path balFileContainingFolder = balFilePath.getParent();
                if (balFileContainingFolder != null && balFileContainingFolder.endsWith(PERSIST_DIRECTORY)) {
                    Path balProjectDir = balFileContainingFolder.getParent();
                    if (balProjectDir != null) {
                        File balProject = balProjectDir.toFile();
                        if (balProject.isDirectory()) {
                            File tomlFile = balProjectDir.resolve(ProjectConstants.BALLERINA_TOML).toFile();
                            return tomlFile.exists();
                        }
                    }
                }
            }
        } catch (UnsupportedOperationException e) {
            //todo log properly This is to identify any issues in resolving path
        }
        return false;
    }
}
