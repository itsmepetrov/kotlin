/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.uast.kotlin.declarations

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.asJava.elements.isGetter
import org.jetbrains.kotlin.asJava.elements.isSetter
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.utils.SmartList
import org.jetbrains.uast.*
import org.jetbrains.uast.java.internal.JavaUElementWithComments
import org.jetbrains.uast.kotlin.*

open class KotlinUMethod(
    psi: PsiMethod,
    final override val sourcePsi: KtDeclaration?,
    givenParent: UElement?
) : KotlinAbstractUElement(givenParent), UMethodTypeSpecific, UAnchorOwner, JavaUElementWithComments, PsiMethod by psi {

    constructor(psi: KtLightMethod, givenParent: UElement?) : this(psi, psi.kotlinOrigin, givenParent)

    override val comments: List<UComment>
        get() = super<KotlinAbstractUElement>.comments

    override val psi: PsiMethod = unwrap<UMethod, PsiMethod>(psi)

    override val javaPsi = psi

    override fun getSourceElement() = sourcePsi ?: this

    private val kotlinOrigin = (psi.originalElement as? KtLightElement<*, *>)?.kotlinOrigin ?: sourcePsi

    override fun getContainingFile(): PsiFile? {
        kotlinOrigin?.containingFile?.let { return it }
        return unwrapFakeFileForLightClass(psi.containingFile)
    }

    override fun getNameIdentifier() = UastLightIdentifier(psi, kotlinOrigin as KtNamedDeclaration?)

    override val annotations by lz {
        psi.annotations
                .mapNotNull { (it as? KtLightElement<*, *>)?.kotlinOrigin as? KtAnnotationEntry }
                .map { KotlinUAnnotation(it, this) }
    }

    private val receiver by lz { (sourcePsi as? KtCallableDeclaration)?.receiverTypeReference }

    override val uastParameters by lz {
        val lightParams = psi.parameterList.parameters
        val receiver = receiver ?: return@lz lightParams.map {
            KotlinUParameter(it, (it as? KtLightElement<*, *>)?.kotlinOrigin, this)
        }
        val receiverLight = lightParams.firstOrNull() ?: return@lz emptyList<UParameter>()
        val uParameters = SmartList<UParameter>(KotlinReceiverUParameter(receiverLight, receiver, this))
        lightParams.drop(1).mapTo(uParameters) { KotlinUParameter(it, (it as? KtLightElement<*, *>)?.kotlinOrigin, this) }
        uParameters
    }

    override val uastAnchor by lazy {
        KotlinUIdentifier(
            nameIdentifier,
            sourcePsi.let { sourcePsi ->
                when (sourcePsi) {
                    is PsiNameIdentifierOwner -> sourcePsi.nameIdentifier
                    is KtObjectDeclaration -> sourcePsi.getObjectKeyword()
                    else -> sourcePsi?.navigationElement
                }
            },
            this
        )
    }


    override val uastBody by lz {
        if (kotlinOrigin?.canAnalyze() != true) return@lz null // EA-137193
        val bodyExpression = when (kotlinOrigin) {
            is KtFunction -> kotlinOrigin.bodyExpression
            is KtProperty -> when {
                psi is KtLightMethod && psi.isGetter -> kotlinOrigin.getter?.bodyExpression
                psi is KtLightMethod && psi.isSetter -> kotlinOrigin.setter?.bodyExpression
                else -> null
            }
            else -> null
        } ?: return@lz null

        wrapExpressionBody(this, bodyExpression)
    }


    override val returnTypeReference: UTypeReferenceExpression? by lz {
        (sourcePsi as? KtCallableDeclaration)?.typeReference?.let {
            LazyKotlinUTypeReferenceExpression(it, this) { javaPsi.returnType ?: UastErrorType }
        }
    }

    override fun equals(other: Any?) = other is KotlinUMethod && psi == other.psi

    companion object {
        fun create(psi: KtLightMethod, containingElement: UElement?): KotlinUMethod {
            val kotlinOrigin = psi.kotlinOrigin
            return if (kotlinOrigin is KtConstructor<*>) {
                KotlinConstructorUMethod(
                    kotlinOrigin.containingClassOrObject,
                    psi,
                    containingElement
                )
            } else if (kotlinOrigin is KtParameter && kotlinOrigin.getParentOfType<KtClass>(true)?.isAnnotation() == true)
                KotlinUAnnotationMethod(psi, containingElement)
            else
                KotlinUMethod(psi, containingElement)
        }
    }
}

open class KotlinUAnnotationMethod(
    psi: KtLightMethod,
    givenParent: UElement?
) : KotlinUMethod(psi, psi.kotlinOrigin, givenParent), UAnnotationMethod {

    override val psi: KtLightMethod = unwrap<UMethod, KtLightMethod>(psi)

    override val uastDefaultValue by lz {
        val annotationParameter = sourcePsi as? KtParameter ?: return@lz null
        val defaultValue = annotationParameter.defaultValue ?: return@lz null
        getLanguagePlugin().convertElement(defaultValue, this) as? UExpression
    }


}

internal fun wrapExpressionBody(function: UElement, bodyExpression: KtExpression): UExpression? = when (bodyExpression) {
    !is KtBlockExpression -> {
        KotlinUBlockExpression.KotlinLazyUBlockExpression(function) { block ->
            val implicitReturn = KotlinUImplicitReturnExpression(block)
            val uBody = function.getLanguagePlugin().convertElement(bodyExpression, implicitReturn) as? UExpression
                ?: return@KotlinLazyUBlockExpression emptyList()
            listOf(implicitReturn.apply { returnExpression = uBody })
        }

    }
    else -> function.getLanguagePlugin().convertElement(bodyExpression, function) as? UExpression
}
