import { TrainingPlanCard } from "./TrainingPlanCard";
import { NutritionAdviceCard } from "./NutritionAdviceCard";
import type { TrainingPlan, NutritionAdvice } from "./types";

const MOCK_TRAINING_PLAN: TrainingPlan = {
  id: "plan-1",
  name: "全身燃脂挑战",
  goal: "fat_loss",
  totalXP: 150,
  streak: 12,
  progress: 0,
  exercises: [
    { id: "ex-1", name: "热身慢跑", sets: 1, reps: 1, duration: 10, completed: true, icon: "🏃", category: "cardio" },
    { id: "ex-2", name: "深蹲", sets: 4, reps: 15, weight: 40, completed: true, icon: "🦵", category: "strength" },
    { id: "ex-3", name: "平板支撑", sets: 3, reps: 1, duration: 60, completed: false, icon: "🧘", category: "core" },
    { id: "ex-4", name: "哑铃卧推", sets: 3, reps: 12, weight: 20, completed: false, icon: "💪", category: "strength" },
    { id: "ex-5", name: "开合跳", sets: 3, reps: 30, completed: false, icon: "⭐", category: "cardio" },
    { id: "ex-6", name: "拉伸放松", sets: 1, reps: 1, duration: 5, completed: false, icon: "🤸", category: "flexibility" },
  ],
};

const MOCK_NUTRITION_ADVICE: NutritionAdvice = {
  proteinRecommendation: "建议补充 30g 蛋白质，促进肌肉修复与生长",
  proteinSources: [
    "鸡胸肉 150g",
    "水煮鸡蛋 4 个",
    "蛋白粉 1 勺",
  ],
  hydrationTips: "训练后至少补充 1500ml 水分，分次小口饮用效果更佳",
  mealSuggestions: [
    {
      name: "三文鱼藜麦沙拉",
      description: "高蛋白低碳水，Omega-3 脂肪酸有助于减轻训练后炎症反应",
      calories: 450,
      protein: 35,
    },
    {
      name: "牛肉西兰花炒饭",
      description: "铁元素与维生素 C 搭配，均衡营养帮助体能恢复",
      calories: 550,
      protein: 30,
    },
    {
      name: "希腊酸奶蓝莓碗",
      description: "益生菌促进肠道吸收，蓝莓富含抗氧化物质",
      calories: 280,
      protein: 20,
    },
  ],
  supplementRecommendations: [
    "可考虑添加肌酸提升训练表现",
    "维生素 D 有助于骨骼健康",
  ],
};

interface FitTrackWidgetPanelProps {
  onClose?: () => void;
}

export function FitTrackWidgetPanel({ onClose }: FitTrackWidgetPanelProps) {
  return (
    <div className="duo-widget-panel">
      {/* 面板头部 */}
      <div className="duo-panel-header">
        <div className="duo-panel-brand">
          <span className="duo-brand-owl">🐱</span>
          <div>
            <h2 className="duo-panel-title">FitTrack</h2>
            <p className="duo-panel-subtitle">今日健康面板</p>
          </div>
        </div>
        {onClose && (
          <button className="duo-close-btn" onClick={onClose} aria-label="关闭面板">
            ✕
          </button>
        )}
      </div>

      {/* 小部件内容区 */}
      <div className="duo-panel-content">
        <TrainingPlanCard
          plan={MOCK_TRAINING_PLAN}
          onComplete={(id) => console.log("Exercise completed:", id)}
          onStartWorkout={() => console.log("Workout started")}
        />
        <NutritionAdviceCard advice={MOCK_NUTRITION_ADVICE} />
      </div>

      {/* 底部信息 */}
      <div className="duo-panel-footer">
        <span>数据更新于 12:30</span>
        <span className="duo-footer-dot">·</span>
        <span>由 AI 营养师生成</span>
      </div>
    </div>
  );
}
