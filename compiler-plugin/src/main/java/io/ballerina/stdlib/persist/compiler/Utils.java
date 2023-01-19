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

import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.tools.diagnostics.Diagnostic;
import io.ballerina.tools.diagnostics.DiagnosticSeverity;

/**
 * Class containing util functions.
 */
public final class Utils {

    private Utils() {
    }

    public static boolean hasCompilationErrors(SyntaxNodeAnalysisContext context) {
        for (Diagnostic diagnostic : context.compilation().diagnosticResult().diagnostics()) {
            if (diagnostic.diagnosticInfo().severity() == DiagnosticSeverity.ERROR) {
                return true;
            }
        }
        return false;
    }

    public static String getTypeName(Node processedTypeNode) {
        String typeName = processedTypeNode.kind().stringValue();
        switch (processedTypeNode.kind()) {
            case HANDLE_TYPE_DESC:
            case ANY_TYPE_DESC:
            case ANYDATA_TYPE_DESC:
            case NEVER_TYPE_DESC:
                // here typename is not empty
                break;
            case UNION_TYPE_DESC:
                typeName = "union";
                break;
            case NIL_TYPE_DESC:
                typeName = "()";
                break;
            case MAP_TYPE_DESC:
                typeName = "map";
                break;
            case ERROR_TYPE_DESC:
                typeName = "error";
                break;
            case STREAM_TYPE_DESC:
                typeName = "stream";
                break;
            case FUNCTION_TYPE_DESC:
                typeName = "function";
                break;
            case TUPLE_TYPE_DESC:
                typeName = "tuple";
                break;
            case TABLE_TYPE_DESC:
                typeName = "table";
                break;
            case DISTINCT_TYPE_DESC:
                typeName = "distinct";
                break;
            case INTERSECTION_TYPE_DESC:
                typeName = "intersection";
                break;
            case FUTURE_TYPE_DESC:
                typeName = "future";
                break;
            case RECORD_REST_TYPE:
                typeName = "in-line record";
                break;
            case OBJECT_TYPE_DESC:
                typeName = "object";
                break;
            default:
                if (typeName.isBlank()) {
                    typeName = processedTypeNode.kind().name();
                }
        }
        return typeName;
    }
}
