package org.bbop.apollo

import org.apache.shiro.SecurityUtils
import org.apache.shiro.authc.AuthenticationException
import org.apache.shiro.authc.UsernamePasswordToken
import org.apache.shiro.web.util.SavedRequest
import org.apache.shiro.web.util.WebUtils

class AuthController {


    def permissionService

    def index = { redirect(action: "login", params: params) }

    def login = {
        WebUtils.saveRequest(request)
        return [ username: params.username, rememberMe: (params.rememberMe != null), targetUri: params.targetUri ]
    }

    def signIn = {
        def authToken = new UsernamePasswordToken(params.username, params.password as String)

        // Support for "remember me"
        if (params.rememberMe) {
            authToken.rememberMe = true
        }
        
        // If a controller redirected to this page, redirect back
        // to it. Otherwise redirect to the root URI.
        def targetUri = params.targetUri ?: "/"
        
        // Handle requests saved by Shiro filters.
        SavedRequest savedRequest = WebUtils.getSavedRequest(request)
        if (savedRequest) {
            println "init targetUri ${targetUri}"
            println "saved request query string ${savedRequest?.queryString}"
            println "saved request requestURi ${savedRequest?.requestURI}"
            println "saved request requestUrl ${savedRequest?.requestUrl}"
            if(savedRequest.queryString && savedRequest.queryString.startsWith("targetUri=")){
                targetUri = savedRequest.queryString.substring("targetUri=".size())
                println "substring from queryString ${targetUri}"
            }
            else{
                targetUri = savedRequest.requestURI - request.contextPath
                println "fix with context path: ${targetUri}"
                if (savedRequest.queryString) {
                    targetUri = targetUri + '?' + savedRequest.queryString
                    println "fix with saved request query string: ${targetUri}"
                }
            }
        }
        
        try{
            // Perform the actual login. An AuthenticationException
            // will be thrown if the username is unrecognised or the
            // password is incorrect.
            permissionService.authenticateWithToken(authToken,request)
//            SecurityUtils.subject.login(authToken)
            if(targetUri){
                log.info "Redirecting to '${targetUri}'."

                redirect(uri: targetUri)
                return
            }
        }
        catch (AuthenticationException ex){
            // Authentication failed, so display the appropriate message
            // on the login page.
            log.info "Authentication failure for user '${params.username}'."
            flash.message = message(code: "login.failed")

            // Keep the username and "remember me" setting so that the
            // user doesn't have to enter them again.
            def m = [ username: params.username ]
            if (params.rememberMe) {
                m["rememberMe"] = true
            }

            // Remember the target URI too.
            if (params.targetUri) {
                m["targetUri"] = params.targetUri
            }

            // Now redirect back to the login page.
            redirect(action: "login", params: m)
        }
    }

    def signOut = {
        // Log the user out of the application.
        SecurityUtils.subject?.logout()
        webRequest.getCurrentRequest().session = null

        // For now, redirect back to the home page.
        redirect(uri: "/")
    }

    def unauthorized = {
//        render "You do not have permission to access this page."
    }
}
