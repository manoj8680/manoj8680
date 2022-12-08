//
//  TestContentController.swift
//  iosAppTests
//
//  Created by Morehouse, Matthew on 9/8/22.
//  Copyright © 2022 orgName. All rights reserved.
//

import XCTest
import MessengerTransport
@testable import iosApp

class TestContentController: MessengerHandler {

    var testExpectation: XCTestExpectation?
    var receivedMessageExpectation: XCTestExpectation?
    var errorExpectation: XCTestExpectation?
    var connectionClosed: XCTestExpectation?
    var receivedMessageText: String? = nil
    var receivedDownloadUrl: String? = nil
    var humanizeEnabled: Bool = true

    override init(deployment: Deployment, reconnectTimeout: Int64 = 60 * 5) {
        super.init(deployment: deployment, reconnectTimeout: reconnectTimeout)

        client.stateChangedListener = { [weak self] stateChange in
            print("State Event. New state: \(stateChange.newState), old state: \(stateChange.oldState)")
            let newState = stateChange.newState
            switch newState {
            case _ as MessagingClientState.Configured:
                self?.testExpectation?.fulfill()
            case _ as MessagingClientState.Closed:
                self?.testExpectation?.fulfill()
            case let error as MessagingClientState.Error:
                print("Socket <error>. code: <\(error.code.description)> , message: <\(error.message ?? "No message")>")
                self?.errorExpectation?.fulfill()
            default:
                break
            }
            self?.onStateChange?(stateChange)
        }

        client.messageListener = { [weak self] message in
            switch message {
            case let messageInserted as MessageEvent.MessageInserted:
                print("Message Inserted: <\(messageInserted.message.description)>")

                // Handling for received messages.
                if messageInserted.message.direction.name == "Outbound" {
                    print("Verifying that the message from the agent/bot has an expected name and an imageUrl attached.")
                    let expectedName = (self?.humanizeEnabled ?? true) ? TestConfig.shared.config?.agentName : nil
                    let expectedUrl = (self?.humanizeEnabled ?? true) ? TestConfig.shared.config?.expectedAvatarUrl : nil

                    // Different checks applied depending on the originating entity.
                    // Expecting the bot to not have a name or avatar. If the deployment config is updated, this may need to change.
                    switch messageInserted.message.from.originatingEntity {
                    case .human:
                        XCTAssertEqual(messageInserted.message.from.name, expectedName, "The agent name was not what was expected.")
                        XCTAssertEqual(messageInserted.message.from.imageUrl, expectedUrl, "The agent avatar url not what was expected.")
                    case .bot:
                        XCTAssertNil(messageInserted.message.from.name, "The bot name was not what was expected.")
                        XCTAssertNil(messageInserted.message.from.imageUrl, "The bot image was not what was expected.")
                    default:
                        XCTFail("Unexpected orginating entity: \(messageInserted.message.from.originatingEntity)")
                    }

                    // Specific expectation to make sure a RECEIVED message is handled.
                    // The normal TestExpectation will just check that ANY messageInserted event is handled.
                    self?.receivedMessageExpectation?.fulfill()
                }
                self?.receivedMessageText = messageInserted.message.text
                self?.testExpectation?.fulfill()
            case let messageUpdated as MessageEvent.MessageUpdated:
                print("Message Updated: <\(messageUpdated.message.description)>")
                self?.testExpectation?.fulfill()
            case let attachmentUpdated as MessageEvent.AttachmentUpdated:
                print("Attachment Updated: <\(attachmentUpdated.attachment.description)>")
                // Only finish the wait when the attachment has finished uploading.
                if let uploadedAttachment = attachmentUpdated.attachment.state as? Attachment.StateUploaded {
                    self?.receivedDownloadUrl = uploadedAttachment.downloadUrl
                    self?.testExpectation?.fulfill()
                }
            case let history as MessageEvent.HistoryFetched:
                print("start of conversation: <\(history.startOfConversation.description)>, messages: <\(history.messages.description)>")
                self?.testExpectation?.fulfill()
            default:
                print("Unexpected messageListener event: \(message)")
            }
            self?.onMessageEvent?(message)
        }

        client.eventListener = { [weak self] event in
            switch event {
            case let typing as Event.AgentTyping:
                print("Agent is typing: \(typing)")
                self?.testExpectation?.fulfill()
            case let closedEvent as Event.ConnectionClosed:
                print("Connection was closed: \(closedEvent)")
                self?.connectionClosed?.fulfill()
            default:
                print("Other event. \(event)")
            }
        }
    }

    func pullDeploymentConfig() -> DeploymentConfig? {
        var deploymentConfig: DeploymentConfig?
        let expectation = XCTestExpectation(description: "Wait for deployment config.")
        self.messengerTransport.fetchDeploymentConfig { config, error in
            if let error = error {
                XCTFail(error.localizedDescription)
            }
            deploymentConfig = config
            expectation.fulfill()
        }
        waitForExpectation(expectation, timeout: 30.0)
        return deploymentConfig
    }

    func startMessengerConnection(file: StaticString = #file, line: UInt = #line) {
        do {
            try connect()
        } catch {
            XCTFail("Possible issue with connecting to the backend: \(error.localizedDescription)", file: file, line: line)
        }
    }
    
    func startMessengerConnectionWithErrorExpectation(_ errorExpectation: XCTestExpectation, file: StaticString = #file, line: UInt = #line) {
        do {
            self.errorExpectation = errorExpectation
            try super.connect()
            waitForErrorExpectation()
        } catch {
            XCTFail("Connect threw other than the expected error: \(error.localizedDescription)", file: file, line: line)
        }
    }

    override func connect() throws {
        testExpectation = XCTestExpectation(description: "Wait for Configuration.")
        try super.connect()
        waitForTestExpectation()
    }

    func disconnectMessenger(file: StaticString = #file, line: UInt = #line) {
        do {
            try disconnect()
        } catch {
            XCTFail("Failed to disconnect the session.\n\(error.localizedDescription)", file: file, line: line)
        }
    }

    override func disconnect() throws {
        testExpectation = XCTestExpectation(description: "Wait for Disconnect.")
        try super.disconnect()
        waitForTestExpectation()
    }

    func sendText(text: String, file: StaticString = #file, line: UInt = #line) {
        do {
            try sendMessage(text: text)
        } catch {
            XCTFail("Failed to send the message '\(text)'\n\(error.localizedDescription)", file: file, line: line)
        }
    }

    func sendTextWithAttribute(text: String, attributes: [String: String], file: StaticString = #file, line: UInt = #line) {
        do {
            try sendMessage(text: text, customAttributes: attributes)
        } catch {
            XCTFail("Failed to send the message \(text) with the attributes: \(attributes)\n\(error.localizedDescription)", file: file, line: line)
        }
    }

    override func sendMessage(text: String, customAttributes: [String: String] = [:]) throws {
        testExpectation = XCTestExpectation(description: "Wait for message to send.")
        try super.sendMessage(text: text, customAttributes: customAttributes)
        waitForTestExpectation()
        verifyReceivedMessage(expectedMessage: text)
    }

    func attemptImageAttach(kotlinByteArray: KotlinByteArray, file: StaticString = #file, line: UInt = #line) {
        do {
            try attachImage(kotlinByteArray: kotlinByteArray)
        } catch {
            XCTFail("Failed to attach image.\n\(error.localizedDescription)", file: file, line: line)
        }
    }

    override func attachImage(kotlinByteArray: KotlinByteArray) throws {
        testExpectation = XCTestExpectation(description: "Wait for image to attach successfully.")
        try super.attachImage(kotlinByteArray: kotlinByteArray)
        waitForTestExpectation()
    }

    func sendUploadedImage(file: StaticString = #file, line: UInt = #line) {
        testExpectation = XCTestExpectation(description: "Wait for the uploaded image url to send.")
        guard let receivedDownloadUrl = receivedDownloadUrl else {
            XCTFail("There was no download URL received.")
            return
        }
        do {
            try super.sendMessage(text: receivedDownloadUrl)
        } catch {
            XCTFail("Failed to upload an image.\n\(error.localizedDescription)", file: file, line: line)
        }
        waitForTestExpectation()
        verifyReceivedMessage(expectedMessage: receivedDownloadUrl)
    }

    override func indicateTyping() throws {
        // No need to wait for an expectation. Just need to make sure the request doesn't fail.
        do {
            try super.indicateTyping()
        } catch {
            XCTFail(error.localizedDescription)
        }
    }

    func waitForTestExpectation(timeout: Double = 60.0) {
        guard let expectation = testExpectation else {
            XCTFail("No expectation to wait for.")
            return
        }
        waitForExpectation(expectation, timeout: timeout)
    }

    func waitForMessageReceiveExpectation(timeout: Double = 60.0) {
        guard let receivedMessageExpectation = receivedMessageExpectation else {
            XCTFail("No received message expectation to wait for.")
            return
        }
        waitForExpectation(receivedMessageExpectation, timeout: timeout)
    }
    
    func waitForErrorExpectation(timeout: Double = 60.0) {
        guard let expectation = errorExpectation else {
            XCTFail("No expectation to wait for.")
            return
        }
        waitForExpectation(expectation, timeout: timeout)
    }
    
    private func waitForExpectation(_ expectation: XCTestExpectation, timeout: Double = 60.0) {
        let result = XCTWaiter().wait(for: [expectation], timeout: timeout)
        XCTAssertEqual(result, .completed, "Test expectation never fullfilled: \(expectation.description)")
    }

    func verifyReceivedMessage(expectedMessage: String) {
        print("Checking the received message.\nExpecting: '\(expectedMessage)'")
        XCTAssertEqual(expectedMessage, receivedMessageText, "The received message: '\(receivedMessageText ?? "")' didn't match what was expected: '\(expectedMessage)'.")
        receivedMessageText = nil
    }

}
