import { useEffect, useState, useRef, useMemo } from "react";
import { getUniqId, scrollToTop, ActionViewItemEnum, getSessionId } from "@/utils";
import querySSE from "@/utils/querySSE";
import {  handleTaskData, combineData } from "@/utils/chat";
import Dialogue from "@/components/Dialogue";
import GeneralInput from "@/components/GeneralInput";
import ActionView from "@/components/ActionView";
import { RESULT_TYPES } from '@/utils/constants';
import { useMemoizedFn } from "ahooks";
import classNames from "classnames";
import Logo from "../Logo";
import { Modal } from "antd";

type Props = {
  inputInfo: CHAT.TInputInfo;
  product?: CHAT.Product;
};

const ChatView: GenieType.FC<Props> = (props) => {
  const { inputInfo: inputInfoProp, product  } = props;

  const [chatTitle, setChatTitle] = useState("");
  const [taskList, setTaskList] = useState<MESSAGE.Task[]>([]);
  const chatList = useRef<CHAT.ChatItem[]>([]);
  const [activeTask, setActiveTask] = useState<CHAT.Task>();
  const [plan, setPlan] = useState<CHAT.Plan>();
  const [showAction, setShowAction] = useState(false);
  const [loading, setLoading] = useState(false);
  const chatRef = useRef<HTMLInputElement>(null);
  const actionViewRef = ActionView.useActionView();
  const sessionId = useMemo(() => getSessionId(), []);
  const [modal, contextHolder] = Modal.useModal();

  const combineCurrentChat = (
    inputInfo: CHAT.TInputInfo,
    sessionId: string,
    requestId: string
  ): CHAT.ChatItem => {
    return {
      query: inputInfo.message!,
      files: inputInfo.files!,
      responseType: "txt",
      sessionId,
      requestId,
      loading: true,
      forceStop: false,
      tasks: [],
      thought: "",
      response: "",
      taskStatus: 0,
      tip: "已接收到你的任务，将立即开始处理...",
      multiAgent: {tasks: []},
    };
  };

  const sendMessage = useMemoizedFn((inputInfo: CHAT.TInputInfo) => {
    const {message, deepThink, outputStyle} = inputInfo;
    const requestId = getUniqId();
    let currentChat = combineCurrentChat(inputInfo, sessionId, requestId);
    chatList.current =  [...chatList.current, currentChat];
    if (!chatTitle) {
      setChatTitle(message!);
    }
    setLoading(true);
    const params = {
      sessionId: sessionId,
      requestId: requestId,
      query: message,
      deepThink: deepThink ? 1 : 0,
      outputStyle
    };

    // 添加详细调试日志
    console.log('🚀 [DEBUG] 开始发送消息');
    console.log('🚀 [DEBUG] 会话ID:', sessionId);
    console.log('🚀 [DEBUG] 请求ID:', requestId);
    console.log('🚀 [DEBUG] 用户查询:', message);
    console.log('🚀 [DEBUG] 深度思考:', deepThink);
    console.log('🚀 [DEBUG] 输出样式:', outputStyle);
    console.log('🚀 [DEBUG] 完整参数:', JSON.stringify(params, null, 2));
    const handleMessage = (data: MESSAGE.Answer) => {
      console.log('📥 [DEBUG] 处理收到的消息:', JSON.stringify(data, null, 2));
      const { finished, resultMap, packageType, status } = data;
      
      console.log('📥 [DEBUG] 消息状态:', status);
      console.log('📥 [DEBUG] 包类型:', packageType);
      console.log('📥 [DEBUG] 是否完成:', finished);
      
      if (status === "tokenUseUp") {
        console.log('⚠️ [DEBUG] Token 用尽，显示提示框');
        modal.info({
          title: '您的试用次数已用尽',
          content: '如需额外申请，请联系 liyang.1236@jd.com',
        });
        const taskData = handleTaskData(
          currentChat,
          deepThink,
          currentChat.multiAgent
        );
        currentChat.loading = false;
        setLoading(false);

        setTaskList(taskData.taskList);
        return;
      }
      if (packageType !== "heartbeat") {
        requestAnimationFrame(() => {
          if (resultMap?.eventData) {
            currentChat = combineData(resultMap.eventData || {}, currentChat);
            const taskData = handleTaskData(
              currentChat,
              deepThink,
              currentChat.multiAgent
            );
            setTaskList(taskData.taskList);
            updatePlan(taskData.plan!);
            openAction(taskData.taskList);
            if (finished) {
              currentChat.loading = false;
              setLoading(false);
            }
            const newChatList = [...chatList.current];
            newChatList.splice(newChatList.length - 1, 1, currentChat);
            chatList.current = newChatList;
          }
        });
        scrollToTop(chatRef.current!);
      }
    };

    const openAction = (taskList:MESSAGE.Task[]) =>{
      if (taskList.filter((t)=>!RESULT_TYPES.includes(t.messageType)).length) {
        setShowAction(true);
      }
    };

    const handleError = (error: unknown) => {
      console.error('💥 [ERROR] 处理错误:', error);
      console.error('💥 [ERROR] 错误类型:', typeof error);
      console.error('💥 [ERROR] 错误字符串:', String(error));
      setLoading(false);
      currentChat.loading = false;
      currentChat.tip = "任务执行失败，请联系管理员！";
      throw error;
    };

    const handleClose = () => {
      console.log('🔚 [DEBUG] SSE 连接关闭');
      setLoading(false);
    };

    querySSE({
      body: params,
      handleMessage,
      handleError,
      handleClose,
    });
  });

  const changeTask = (task: CHAT.Task) => {
    actionViewRef.current?.changeActionView(ActionViewItemEnum.follow);
    changeActionStatus(true);
    setActiveTask(task);
  };

  const updatePlan = (plan: CHAT.Plan) => {
    setPlan(plan);
  };

  const changeFile = (file: CHAT.TFile) => {
    changeActionStatus(true);
    actionViewRef.current?.setFilePreview(file);
  };

  const changePlan = () => {
    changeActionStatus(true);
    actionViewRef.current?.openPlanView();
  };

  const changeActionStatus = (status: boolean) => {
    setShowAction(status);
  };

  useEffect(() => {
    if (inputInfoProp.message?.length !== 0) {
      sendMessage(inputInfoProp);
    }
  }, [inputInfoProp, sendMessage]);

  return (
    <div className="h-full w-full flex justify-center">
      <div
        className={classNames("p-24 flex flex-col flex-1 w-0", { 'max-w-[1200px]': !showAction })}
        id="chat-view"
      >
        <div className="w-full flex justify-between">
          <div className="w-full flex items-center pb-8">
            <Logo />
            <div className="overflow-hidden whitespace-nowrap text-ellipsis text-[16px] font-[500] text-[#27272A] mr-8">
              {chatTitle}
            </div>
            {inputInfoProp.deepThink && <div className="rounded-[4px] px-6 border-1 border-solid border-gray-300 flex items-center shrink-0">
              <i className="font_family icon-shendusikao mr-6 text-[12px]"></i>
              <span className="ml-[-4px]">深度研究</span>
            </div>}
          </div>
        </div>
        <div
          className="w-full flex-1 overflow-auto no-scrollbar mb-[36px]"
          ref={chatRef}
        >
          {chatList.current.map((chat) => {
            return <div key={chat.requestId}>
              <Dialogue
                chat={chat}
                deepThink={inputInfoProp.deepThink}
                changeTask={changeTask}
                changeFile={changeFile}
                changePlan={changePlan}
              />
            </div>;
          })}
        </div>
        <GeneralInput
          placeholder={loading ? "任务进行中" : "希望 Genie 为你做哪些任务呢？"}
          showBtn={false}
          size="medium"
          disabled={loading}
          product={product}
          // 多轮问答也不支持切换deepThink，使用传进来的
          send={(info) => sendMessage({
            ...info,
            deepThink: inputInfoProp.deepThink
          })}
        />
      </div>
      {contextHolder}
      <div className={classNames('transition-all w-0', {
        'opacity-0 overflow-hidden': !showAction,
        'flex-1': showAction,
      })}>
        <ActionView
          activeTask={activeTask}
          taskList={taskList}
          plan={plan}
          ref={actionViewRef}
          onClose={() => changeActionStatus(false)}
        />
      </div>
    </div>
  );
};

export default ChatView;
