/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.controllers.android;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnGenericMotionListener;
import android.view.View.OnKeyListener;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.LifecycleListener;
import com.badlogic.gdx.backends.android.AndroidInput;
import com.badlogic.gdx.controllers.AbstractControllerManager;
import com.badlogic.gdx.controllers.ControllerListener;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntMap;
import com.badlogic.gdx.utils.IntMap.Entry;
import com.badlogic.gdx.utils.Pool;

public class AndroidControllers extends AbstractControllerManager implements LifecycleListener, OnKeyListener, OnGenericMotionListener {
	private final static String TAG = "AndroidControllers";
	public static boolean ignoreNoGamepadButtons = true;
	public static boolean useNewAxisLogic = true;
	private final IntMap<AndroidController> controllerMap = new IntMap<AndroidController>();
	private final Array<ControllerListener> listeners = new Array<ControllerListener>();
	private final Array<AndroidControllerEvent> eventQueue = new Array<AndroidControllerEvent>();
	private final Pool<AndroidControllerEvent> eventPool = new Pool<AndroidControllerEvent>() {
		@Override
		protected AndroidControllerEvent newObject () {
			return new AndroidControllerEvent();
		}
	};

	public AndroidControllers() {
		listeners.add(new ManageCurrentControllerListener());
		Gdx.app.addLifecycleListener(this);
		gatherControllers(false);
		setupEventQueue();
		((AndroidInput)Gdx.input).addKeyListener(this);
		((AndroidInput)Gdx.input).addGenericMotionListener(this);
		
		// use InputManager on Android +4.1 to receive (dis-)connect events
		if(Gdx.app.getVersion() >= 16) {
			try {
				String className = "com.badlogic.gdx.controllers.android.ControllerLifeCycleListener";
				Class.forName(className).getConstructor(AndroidControllers.class).newInstance(this);
			} catch(Exception e) {
				Gdx.app.log(TAG, "Couldn't register controller life-cycle listener");
			}
		}
	}
	
	private void setupEventQueue() {
		new Runnable() {
			@SuppressWarnings("synthetic-access")
			@Override
			public void run () {
				synchronized(eventQueue) {
					for(AndroidControllerEvent event: eventQueue) {
						switch(event.type) {
							case AndroidControllerEvent.CONNECTED:
								controllers.add(event.controller);
								for(ControllerListener listener: listeners) {
									listener.connected(event.controller);
								}
								break;
							case AndroidControllerEvent.DISCONNECTED:
								controllers.removeValue(event.controller, true);
								for(ControllerListener listener: listeners) {
									listener.disconnected(event.controller);
								}
								for(ControllerListener listener: event.controller.getListeners()) {
									listener.disconnected(event.controller);
								}
								break;
							case AndroidControllerEvent.BUTTON_DOWN:
								event.controller.buttons.put(event.code, event.code);
								for(ControllerListener listener: listeners) {
									if(listener.buttonDown(event.controller, event.code)) break;
								}
								for(ControllerListener listener: event.controller.getListeners()) {
									if(listener.buttonDown(event.controller, event.code)) break;
								}
								break;
							case AndroidControllerEvent.BUTTON_UP:
								event.controller.buttons.remove(event.code, 0);
								for(ControllerListener listener: listeners) {
									if(listener.buttonUp(event.controller, event.code)) break;
								}
								for(ControllerListener listener: event.controller.getListeners()) {
									if(listener.buttonUp(event.controller, event.code)) break;
								}
								break;
							case AndroidControllerEvent.AXIS:
								event.controller.axes[event.code] = event.axisValue;
								for(ControllerListener listener: listeners) {
									if(listener.axisMoved(event.controller, event.code, event.axisValue)) break;
								}
								for(ControllerListener listener: event.controller.getListeners()) {
									if(listener.axisMoved(event.controller, event.code, event.axisValue)) break;
								}
								break;
							default:
						}
					}
					eventPool.freeAll(eventQueue);
					eventQueue.clear();
				}
				Gdx.app.postRunnable(this);
			}
		}.run();
	}
	
	@Override
	public boolean onGenericMotion (View view, MotionEvent motionEvent) {
		if((motionEvent.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) == 0) return false;
		AndroidController controller = controllerMap.get(motionEvent.getDeviceId());
		if(controller != null) {
			synchronized(eventQueue) {
				if (controller.hasPovAxis()) {
					float povX = motionEvent.getAxisValue(MotionEvent.AXIS_HAT_X);
					float povY = motionEvent.getAxisValue(MotionEvent.AXIS_HAT_Y);
					// map axis movement to dpad buttons
					if (povX != controller.povX) {
						if (controller.povX == 1f) {
							AndroidControllerEvent event = eventPool.obtain();
							event.controller = controller;
							event.type = AndroidControllerEvent.BUTTON_UP;
							event.code = KeyEvent.KEYCODE_DPAD_RIGHT;
							eventQueue.add(event);
						} else if (controller.povX == -1f) {
							AndroidControllerEvent event = eventPool.obtain();
							event.controller = controller;
							event.type = AndroidControllerEvent.BUTTON_UP;
							event.code = KeyEvent.KEYCODE_DPAD_LEFT;
							eventQueue.add(event);
						}

						if (povX == 1f) {
							AndroidControllerEvent event = eventPool.obtain();
							event.controller = controller;
							event.type = AndroidControllerEvent.BUTTON_DOWN;
							event.code = KeyEvent.KEYCODE_DPAD_RIGHT;
							eventQueue.add(event);
						} else if (povX == -1f) {
							AndroidControllerEvent event = eventPool.obtain();
							event.controller = controller;
							event.type = AndroidControllerEvent.BUTTON_DOWN;
							event.code = KeyEvent.KEYCODE_DPAD_LEFT;
							eventQueue.add(event);
						}
						controller.povX = povX;
					}

					if (povY != controller.povY) {
						if (controller.povY == 1f) {
							AndroidControllerEvent event = eventPool.obtain();
							event.controller = controller;
							event.type = AndroidControllerEvent.BUTTON_UP;
							event.code = KeyEvent.KEYCODE_DPAD_DOWN;
							eventQueue.add(event);
						} else if (controller.povY == -1f) {
							AndroidControllerEvent event = eventPool.obtain();
							event.controller = controller;
							event.type = AndroidControllerEvent.BUTTON_UP;
							event.code = KeyEvent.KEYCODE_DPAD_UP;
							eventQueue.add(event);
						}

						if (povY == 1f) {
							AndroidControllerEvent event = eventPool.obtain();
							event.controller = controller;
							event.type = AndroidControllerEvent.BUTTON_DOWN;
							event.code = KeyEvent.KEYCODE_DPAD_DOWN;
							eventQueue.add(event);
						} else if (povY == -1f) {
							AndroidControllerEvent event = eventPool.obtain();
							event.controller = controller;
							event.type = AndroidControllerEvent.BUTTON_DOWN;
							event.code = KeyEvent.KEYCODE_DPAD_UP;
							eventQueue.add(event);
						}
						controller.povY = povY;

					}
				}

				if (controller.hasTriggerAxis()){
					float lTrigger = motionEvent.getAxisValue(MotionEvent.AXIS_LTRIGGER);
					float rTrigger = motionEvent.getAxisValue(MotionEvent.AXIS_RTRIGGER);
					//map axis movement to trigger buttons
					if (lTrigger != controller.lTrigger){
						if (lTrigger == 1){
							AndroidControllerEvent event = eventPool.obtain();
							event.controller = controller;
							event.type = AndroidControllerEvent.BUTTON_DOWN;
							event.code = KeyEvent.KEYCODE_BUTTON_L2;
							eventQueue.add(event);
						} else if (lTrigger == 0){
							AndroidControllerEvent event = eventPool.obtain();
							event.controller = controller;
							event.type = AndroidControllerEvent.BUTTON_UP;
							event.code = KeyEvent.KEYCODE_BUTTON_L2;
							eventQueue.add(event);
						}
						controller.lTrigger = lTrigger;

					}

					if (rTrigger != controller.rTrigger){
						if (rTrigger == 1){
							AndroidControllerEvent event = eventPool.obtain();
							event.controller = controller;
							event.type = AndroidControllerEvent.BUTTON_DOWN;
							event.code = KeyEvent.KEYCODE_BUTTON_R2;
							eventQueue.add(event);
						} else if (rTrigger == 0){
							AndroidControllerEvent event = eventPool.obtain();
							event.controller = controller;
							event.type = AndroidControllerEvent.BUTTON_UP;
							event.code = KeyEvent.KEYCODE_BUTTON_R2;
							eventQueue.add(event);
						}
						controller.rTrigger = rTrigger;

					}
				}

				int axisIndex = 0;
            	for (int axisId: controller.axesIds) {
					float axisValue = motionEvent.getAxisValue(axisId);
					if(controller.getAxis(axisIndex) == axisValue) {
						axisIndex++;
						continue;
					}
					AndroidControllerEvent event = eventPool.obtain();
					event.type = AndroidControllerEvent.AXIS;
					event.controller = controller;
					event.code = axisIndex;
					event.axisValue = axisValue;
					eventQueue.add(event);
					axisIndex++;
				}
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean onKey (View view, int keyCode, KeyEvent keyEvent) {
		if (ignoreNoGamepadButtons && !KeyEvent.isGamepadButton(keyCode)) {
			return false;
		}
		AndroidController controller = controllerMap.get(keyEvent.getDeviceId());
		if(controller != null) {
			if(controller.getButton(keyCode) && keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
				return true;
			}
			//ignore any button trigger input if there is a trigger axis
			if (controller.hasTriggerAxis() && (keyCode == KeyEvent.KEYCODE_BUTTON_L2 || keyCode == KeyEvent.KEYCODE_BUTTON_R2)){
				return true;
			}
			synchronized(eventQueue) {
				AndroidControllerEvent event = eventPool.obtain();
				event.controller = controller;
				if(keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
					event.type = AndroidControllerEvent.BUTTON_DOWN;
				} else {
					event.type = AndroidControllerEvent.BUTTON_UP;
				}
				event.code = keyCode;
				eventQueue.add(event);
			}
			return keyCode != KeyEvent.KEYCODE_BACK || Gdx.input.isCatchBackKey();
		} else {
			return false;
		}
	}
	
	private void gatherControllers(boolean sendEvent) {
		// gather all joysticks and gamepads, remove any disconnected ones
		IntMap<AndroidController> removedControllers = new IntMap<AndroidController>();
		removedControllers.putAll(controllerMap);
		
		for(int deviceId: InputDevice.getDeviceIds()) {
			AndroidController controller = controllerMap.get(deviceId);
			if(controller != null) {
				removedControllers.remove(deviceId);
			} else {
				addController(deviceId, sendEvent);
			}
		}
		
		for(Entry<AndroidController> entry: removedControllers.entries()) {
			removeController(entry.key);
		}
	}
	
	protected void addController(int deviceId, boolean sendEvent) {
		try {
			InputDevice device = InputDevice.getDevice(deviceId);
			if (!isController(device)) return;
			String name = device.getName();
			AndroidController controller = new AndroidController(deviceId, name);
			controllerMap.put(deviceId, controller);
			if (sendEvent) {
				synchronized (eventQueue) {
					AndroidControllerEvent event = eventPool.obtain();
					event.type = AndroidControllerEvent.CONNECTED;
					event.controller = controller;
					eventQueue.add(event);
				}
			} else {
				controllers.add(controller);
			}
			Gdx.app.log(TAG, "added controller '" + name + "'");
		} catch (RuntimeException e) {
			// this exception is sometimes thrown by getDevice().
			// we can't use this device anyway, so ignore it and move on
			Gdx.app.error(TAG, "Could not get information about " + deviceId +
							", ignoring the device.", e);
		}
	}
	
	protected void removeController(int deviceId) {
		AndroidController controller = controllerMap.remove(deviceId);
		if(controller != null) {
			synchronized(eventQueue) {
				AndroidControllerEvent event = eventPool.obtain();
				controller.connected = false;
				event.type = AndroidControllerEvent.DISCONNECTED;
				event.controller = controller;
				eventQueue.add(event);
			}
			Gdx.app.log(TAG, "removed controller '" + controller.getName() + "'");
		}
	}
	
	private boolean isController(InputDevice device) {
		return ((device.getSources() & InputDevice.SOURCE_CLASS_JOYSTICK) == InputDevice.SOURCE_CLASS_JOYSTICK)
				&& (((device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
				|| (device.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC))
				&& !"uinput-fpc".equals(device.getName());
	}

	@Override
	public void addListener (ControllerListener listener) {
		synchronized(eventQueue) {
			listeners.add(listener);
		}
	}

	@Override
	public void removeListener (ControllerListener listener) {
		synchronized(eventQueue) {
			listeners.removeValue(listener, true);
		}
	}

	@Override
	public void pause () {
		Gdx.app.log(TAG, "controllers paused");
	}

	@Override
	public void resume () {
		gatherControllers(true);
		Gdx.app.log(TAG, "controllers resumed");		
	}

	@Override
	public void dispose () {
	}

	@Override
	public void clearListeners () {
		listeners.clear();
		listeners.add(new ManageCurrentControllerListener());
	}

	@Override
	public Array<ControllerListener> getListeners () {
		return listeners;
	}
}