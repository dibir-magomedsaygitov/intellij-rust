/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rustSlowTests.lang.resolve

import com.intellij.codeInspection.InspectionProfileEntry
import org.junit.ComparisonFailure
import org.rust.ExpandMacros
import org.rust.TestProject
import org.rust.WithExperimentalFeatures
import org.rust.cargo.RsWithToolchainTestBase
import org.rust.ide.annotator.RsAnnotationTestFixture
import org.rust.ide.experiments.RsExperiments.EVALUATE_BUILD_SCRIPTS
import org.rust.ide.experiments.RsExperiments.PROC_MACROS
import org.rust.ide.inspections.lints.RsUnusedImportInspection
import org.rust.lang.core.macros.MacroExpansionScope
import kotlin.reflect.KClass
import kotlin.test.assertFailsWith

class CargoProjectAnnotateTest : RsWithToolchainTestBase() {

    @ExpandMacros(MacroExpansionScope.WORKSPACE)
    @WithExperimentalFeatures(EVALUATE_BUILD_SCRIPTS, PROC_MACROS)
    fun `test unused imports, usages in serde attribute string (current mod)`() = buildProject {
        toml("Cargo.toml", """
            [package]
            name = "hello"
            version = "0.1.0"
            authors = []
            edition = "2018"

            [dependencies]
            serde = { version = "1.0", features = ["derive"] }
            serde_json = "1.0"
        """)

        dir("src") {
            rust("lib.rs", """
                mod inner {
                    pub fn func() -> i32 { 1 }
                }
                use inner::func;

                #[derive(serde::Deserialize)]
                struct Foo {
                    #[serde(default = "func")]
                    field: i32,
                }
            """)
        }
    }.checkHighlighting(RsUnusedImportInspection::class)

    @ExpandMacros(MacroExpansionScope.WORKSPACE)
    @WithExperimentalFeatures(EVALUATE_BUILD_SCRIPTS, PROC_MACROS)
    fun `test unused imports, usages in serde attribute string (other mod)`() = assertFailsWith<ComparisonFailure> {
        buildProject {
            toml("Cargo.toml", """
                [package]
                name = "hello"
                version = "0.1.0"
                authors = []
                edition = "2018"

                [dependencies]
                serde = { version = "1.0", features = ["derive"] }
                serde_json = "1.0"
            """)

            dir("src") {
                rust("lib.rs", """
                    mod mod1 {
                        pub fn func() -> i32 { 1 }
                    }
                    use mod1::func;

                    mod mod2 {
                        #[derive(serde::Deserialize)]
                        struct Foo {
                            #[serde(default = "super::func")]
                            field: i32,
                        }
                    }
                """)
            }
        }.checkHighlighting(RsUnusedImportInspection::class)
    }

    private fun TestProject.checkHighlighting(inspectionClass: KClass<out InspectionProfileEntry>) {
        myFixture.configureByFile("src/lib.rs")
        myFixture.openFileInEditor(file("src/lib.rs"))
        val annotationFixture = RsAnnotationTestFixture<Unit>(this@CargoProjectAnnotateTest, myFixture, inspectionClasses = listOf(inspectionClass))
        annotationFixture.setUp()
        try {
            myFixture.checkHighlighting(true, false, false)
        } finally {
            annotationFixture.tearDown()
        }
    }
}
